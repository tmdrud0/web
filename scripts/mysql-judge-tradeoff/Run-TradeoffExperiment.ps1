[CmdletBinding()]
param(
    [ValidateSet("rabbit", "mysql")][string]$DispatchMode = "rabbit",
    [double]$TargetRps = 20,
    [int]$DurationSeconds = 30,
    [int]$RampSeconds = 5,
    [int]$WorkerCount = 16,
    [int]$MySqlClaimBatchSize = 16,
    [int]$MySqlMaxInFlight = 16,
    [string]$MySqlClaimTimeout = "10s",
    [string]$MySqlPollInterval = "100ms",
    [int]$RabbitPrefetch = 1,
    [long]$LatencySeed = 20260919,
    [switch]$FaultEnabled,
    [int]$FaultAtSeconds = 15,
    [ValidateSet("judge-1", "judge-2")][string]$KilledNode = "judge-1",
    [int]$DownDurationSeconds = 15,
    [int]$DrainTimeoutSeconds = 180,
    [int]$UserCount = 2000,
    [string]$RunId = "",
    [switch]$KeepStack,
    [switch]$DryRun,
    # Staircase (steady-state capacity sweep). With -Staircase the run does a warm-up hold followed
    # by one hold per -StageRps entry, all inside one stack/JVM lifetime, and -TargetRps,
    # -DurationSeconds, and -FaultEnabled no longer apply.
    [switch]$Staircase,
    [string]$StageRps = "50,50,100,150,200,230",
    [int]$WarmupStageCount = 1,
    [int]$StageHoldSeconds = 30,
    [int]$SteadyGuardSeconds = 3,
    [double]$OverloadThresholdRowsPerSec = 1.0,
    [int]$AssertMinSuccessPercent = 95,
    [int]$AssertP95Millis = 60000,
    [int]$TraceAlignmentToleranceSeconds = 5,
    # Fault-free claim-timeout experiment. One stack and one server JVM lifetime hold two phases: a
    # warm-up phase in its own contest, a full drain to quiescence, then a measured phase in a second
    # contest. -TargetRps is the load for both phases and -MySqlClaimTimeout is the variable under
    # test; -Staircase and -NormalTimeout are separate experiments and are not combined.
    [switch]$NormalTimeout,
    [int]$WarmupSeconds = 30,
    [int]$MeasurementSeconds = 60,
    # The warm-up phase's own offered rate. 0 means "the same as -TargetRps", which is what every
    # earlier phased run used and is what keeps this an added knob rather than a changed one. A burst
    # experiment needs the two phases to differ: warming up at the measured rate would build the very
    # backlog the measurement exists to observe, and the quiescence gate would then have to drain it
    # before the baseline could be taken.
    [double]$WarmupTargetRps = 0,
    # Session preparation, separated from the measured submission load. This is the window over which
    # each phase's population establishes its sessions: it is the injector's ramp, so the logins are
    # spread over it at population/AuthPrepSeconds per second instead of arriving together, and every
    # user's first submission is held until it closes. It must equal -RampSeconds, because the ramp's
    # end is the instant the submissions are released at and the hold's traced start is the boundary
    # the measured window is derived from. 0 leaves the model exactly as every earlier run used it -
    # log in as you start, submit as soon as you are logged in.
    [int]$AuthPrepSeconds = 0,
    # Ingress preflight, run against the fresh stack before the warm-up: repeat the readiness probe
    # through the published port, establish the measurement's own session population over the same
    # preparation window the measurement will use, submit briefly, and probe login-plus-submit once.
    # Its contest and user pool are separate, so nothing it writes can land in a measured window. A
    # refusal here stops the run before either condition is spent on an ingress that cannot carry it.
    [switch]$IngressPreflight,
    [int]$PreflightHoldSeconds = 2,
    # How long the preflight waits for readiness through the published port to report UP before it
    # calls the ingress down. A freshly started stack can answer 5xx on the readiness endpoint for a
    # while - the endpoint's first call initializes its indicators - so this is a wait with a
    # deadline, not a sample: "readiness succeeds repeatedly" is a claim about a state, and a state
    # has to be observed rather than assumed from one instant.
    [int]$PreflightReadinessTimeoutSeconds = 180,
    # Which preflight user the single login-plus-submit probe uses. Any of them proves the same
    # thing; fixing it makes the probe reproducible.
    [int]$PreflightProbeUserIndex = 1,
    # Fail-stop recovery comparison. Same two-phase machinery as -NormalTimeout - a warm-up contest,
    # a full drain to quiescence, then a measured contest - but the measured phase SIGKILLs one judge
    # node once that node actually holds work, keeps the load running through the outage, restarts the
    # node with identical settings and observes the pipeline until it has recovered. This is not the
    # legacy -FaultEnabled path: that one kills at a fixed second and needs no active work to exist,
    # which is exactly the confound this mode removes. -FaultEnabled and -FaultRecovery are separate
    # switches and are never combined.
    [switch]$FaultRecovery,
    # The trigger is a condition on the target node, not a clock. The window opens this many seconds
    # after the measured window starts - the experiment needs a pre-fault steady stretch longer than
    # the fault's own observation window - and then waits up to FaultTriggerWaitSeconds for the
    # primary condition. Failing that it accepts the fallback condition; failing that it injects
    # anyway and marks the run so it cannot be reported as a recovery measurement.
    [int]$FaultMinSteadySeconds = 30,
    [int]$FaultTriggerWaitSeconds = 15,
    [int]$FaultMinRunning = 1,
    [int]$FaultMinReserved = 4,
    [int]$FaultFallbackMinReserved = 1
)

$ErrorActionPreference = "Stop"

# The application assigns a latency class with FNV-1a over Java UTF-16 characters followed by
# SplitMix64. Keep the raw latency export independently reproducible by using the same bit-level
# algorithm here rather than inferring a class from an observed duration or an outbox attempt.
if ($null -eq ("MysqlJudgeTradeoff.LatencyClassifier" -as [type])) {
    Add-Type -TypeDefinition @"
namespace MysqlJudgeTradeoff {
    public static class LatencyClassifier {
        public static bool IsSlow(long seed, string value, double slowRatio) {
            ulong hash = 0xcbf29ce484222325UL;
            unchecked {
                foreach (char character in value) {
                    hash ^= character;
                    hash *= 0x100000001b3UL;
                }
                ulong mixed = ((ulong)seed) ^ hash;
                mixed = (mixed ^ (mixed >> 30)) * 0xbf58476d1ce4e5b9UL;
                mixed = (mixed ^ (mixed >> 27)) * 0x94d049bb133111ebUL;
                mixed ^= mixed >> 31;
                double draw = (mixed >> 11) * (1.0 / 9007199254740992.0);
                return draw < slowRatio;
            }
        }
    }
}
"@
}
if ([MysqlJudgeTradeoff.LatencyClassifier]::IsSlow(20260920, "stable-work-item", 0.05) -or
    -not [MysqlJudgeTradeoff.LatencyClassifier]::IsSlow(20260920, "fixture-15", 0.05)) {
    throw "PowerShell latency classifier does not match the Java deterministic-class fixture."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeArgs = @("-p", "oj-loadtest", "-f", "compose.yaml", "-f", "compose.loadtest.yaml")
$baseUrl = "http://127.0.0.1:18080"
$dbName = "oj_loadtest"
# The date pattern is a separate -f operand on purpose: inside a single format string the "t" of
# "rabbit" and the "m"/"s"/"y" of "mysql" are .NET date specifiers, so a default RunId came out as
# "20260920-081149-rabbi오" and failed the character check below.
if (-not $RunId) { $RunId = "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $DispatchMode }
if ($RunId -notmatch '^[A-Za-z0-9._-]+$') { throw "RunId may contain only letters, digits, dot, underscore, and dash." }
if ($TargetRps -le 0 -or $DurationSeconds -lt 5 -or $RampSeconds -lt 0) { throw "TargetRps must be positive and DurationSeconds must be >= 5." }
if ($WorkerCount -lt 1 -or $MySqlClaimBatchSize -lt 1 -or $MySqlMaxInFlight -lt 1 -or $RabbitPrefetch -lt 1) { throw "Worker, batch, in-flight, and prefetch values must be positive." }
if ($FaultEnabled -and ($FaultAtSeconds -le 0 -or $FaultAtSeconds -ge ($RampSeconds + $DurationSeconds))) { throw "FaultAtSeconds must fall inside the Gatling run." }

# Spring Boot binds this property with DurationStyle, whose simple form is
# ^([+-]?\d+)([a-zA-Z]{0,2})$ - digits only, no decimal point - so a fractional value written the
# natural way, such as "2.5s", fails to bind and the app does not start. The simple form does accept
# a millisecond unit, so a fractional second is passed as whole milliseconds, which is the same
# Duration exactly rather than a rounded one. The requested value is what the run and the document
# report; the property form is recorded alongside it so the binding is auditable.
if ($MySqlClaimTimeout -match '^([+-]?\d+)([a-zA-Z]{0,2})$') {
    $claimTimeoutProperty = $MySqlClaimTimeout
} elseif ($MySqlClaimTimeout -match '^(\d+(?:\.\d+)?)s$') {
    $claimTimeoutProperty = "{0}ms" -f [long][math]::Round([double]$Matches[1] * 1000)
} elseif ($MySqlClaimTimeout -match '^[+-]?[pP]') {
    # ISO-8601, the other form DurationStyle detects, passed through unchanged.
    $claimTimeoutProperty = $MySqlClaimTimeout
} else {
    throw "-MySqlClaimTimeout '$MySqlClaimTimeout' is not bindable: use a whole number of milliseconds, seconds, minutes or hours (for example 2500ms), or an ISO-8601 duration such as PT2.5S."
}

# A negative or zero lease is accepted by the regex above and by the app, which maps it to
# Duration.ZERO - every PUBLISHING row would then be reclaimable on the next poll, which is not an
# experiment but a broken configuration. Refused here so it cannot be run by accident.
if ($claimTimeoutProperty -match '^([+-]?\d+)') {
    if ([double]$Matches[1] -le 0) {
        throw "-MySqlClaimTimeout '$MySqlClaimTimeout' is not a usable lease: it must be greater than zero."
    }
} elseif ($claimTimeoutProperty -match '^[+-]?[pP]') {
    try {
        if ([System.Xml.XmlConvert]::ToTimeSpan($claimTimeoutProperty).TotalMilliseconds -le 0) {
            throw "-MySqlClaimTimeout '$MySqlClaimTimeout' is not a usable lease: it must be greater than zero."
        }
    } catch [System.FormatException] {
        throw "-MySqlClaimTimeout '$MySqlClaimTimeout' is not an ISO-8601 duration."
    }
}

$stageRpsList = @()
if ($Staircase) {
    if ($FaultEnabled) { throw "-Staircase measures steady-state capacity and does not inject faults." }
    $stageRpsList = @($StageRps -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { [double]$_ })
    if ($stageRpsList.Count -lt 2) { throw "-StageRps must list a warm-up stage and at least one measured stage." }
    if (@($stageRpsList | Where-Object { $_ -le 0 }).Count -gt 0) { throw "Every -StageRps entry must be greater than 0." }
    if ($WarmupStageCount -lt 0 -or $WarmupStageCount -ge $stageRpsList.Count) { throw "-WarmupStageCount must leave at least one measured stage." }
    if ($StageHoldSeconds -lt 12) { throw "-StageHoldSeconds must be at least 12 so a stage has enough samples to classify." }
    # The analyzer refuses to call a stage steady or overloaded on fewer than 10 backlog samples, and
    # the sampler can run at up to about 1.5s per tick, so a measured window shorter than this would
    # produce a run whose every stage comes back unclassified.
    if ($SteadyGuardSeconds -lt 0 -or ($StageHoldSeconds - $SteadyGuardSeconds) -lt 12) { throw "-SteadyGuardSeconds must leave at least 12 measured seconds inside each hold, or the analyzer cannot classify the stage." }
    if ($UserCount -lt 1000) { throw "Staircase runs require -UserCount of at least 1000." }
    if ($DrainTimeoutSeconds -lt 300) { throw "Staircase runs require -DrainTimeoutSeconds of at least 300." }
}

# The three staged experiments share the machinery - a traced hold analysed per window, one stack for
# the whole run - but not their parameters: the staircase sweeps a ladder of rates in one run, the
# normal-timeout run holds a single rate so that the claim timeout is the only thing that changes
# between runs, and the fault-recovery run holds a single rate and then removes a judge node from the
# cluster. They are kept apart rather than merged into one mode because the staircase's ladder is what
# defines its stage labels and the other two differ in what they do to the cluster mid-hold.
$phasedLoad = [bool]$NormalTimeout -or [bool]$FaultRecovery
$stagedLoad = [bool]$Staircase -or $phasedLoad
if ($Staircase -and $NormalTimeout) { throw "-Staircase and -NormalTimeout are different experiments; pass one of them." }
if ($Staircase -and $FaultRecovery) { throw "-Staircase and -FaultRecovery are different experiments; pass one of them." }
if ($NormalTimeout -and $FaultRecovery) { throw "-NormalTimeout and -FaultRecovery are different experiments; pass one of them." }
if ($FaultEnabled -and $FaultRecovery) { throw "-FaultEnabled kills at a fixed second and -FaultRecovery triggers on active work; pass one of them." }
$warmupPrefix = ""
$measurementPrefix = ""
$preflightPrefix = ""
$measurementHoldSeconds = 0
# Session preparation is a phased-run concept: the gate is the end of the injector's ramp, and the
# phased path is the one that traces its ramp. Refused rather than ignored, because a run that asked
# for the separation and silently did not get it would measure the login storm it means to remove.
if ($AuthPrepSeconds -gt 0 -and -not $phasedLoad) {
    throw "-AuthPrepSeconds separates session preparation from the measured load in a phased (-NormalTimeout/-FaultRecovery) run; it does not apply to this one."
}
if ($AuthPrepSeconds -lt 0) { throw "-AuthPrepSeconds must not be negative; 0 is the model that submits as it logs in." }
if ($IngressPreflight -and -not $phasedLoad) { throw "-IngressPreflight establishes a phased run's session population before its warm-up; it does not apply to this one." }
if ($phasedLoad) {
    # The fault-recovery run is still mysql-only: its whole subject is what a killed claim lease
    # does to the rows it held, and rabbit has no claim lease to lose. The normal-timeout run holds
    # one rate across two contests and says nothing about the claim protocol, so it is meaningful
    # for either dispatch path - and a rabbit capacity question is exactly that shape.
    if ($DispatchMode -ne "mysql" -and -not ($NormalTimeout -and $DispatchMode -eq "rabbit")) {
        throw "A phased-load run is mysql, or rabbit with -NormalTimeout; -FaultRecovery measures a MySQL claim lease."
    }
    if ($FaultEnabled) { throw "A phased-load run drives the fault from its own trigger, so -FaultEnabled does not apply to it." }
    if (-not $PSBoundParameters.ContainsKey("TargetRps")) { throw "A phased-load run requires an explicit -TargetRps: the offered rate is an input to the comparison, not a default." }
    # The floor is 10s rather than 12: a burst experiment holds exactly ten seconds of steady load on
    # purpose, and a hold lengthened to satisfy a validation floor is not the burst that was asked for.
    # The old floor existed so that a measured window would hold at least the ten backlog samples the
    # classifier reads, and a 10s window at the sampler's ~1s tick sits exactly on that edge. When it
    # falls short the analyzer reports the stage as unclassified and says why - a recorded outcome,
    # not a lost run - so the guarantee this floor protected is now carried by the report instead.
    if ($WarmupSeconds -lt 10) { throw "-WarmupSeconds must be at least 10, or the warm-up phase is too short to have warmed anything." }
    if ($MeasurementSeconds -lt 10) { throw "-MeasurementSeconds must be at least 10 so a measured window has enough samples to read." }
    if ($SteadyGuardSeconds -lt 0) { throw "-SteadyGuardSeconds must not be negative." }
    if ($UserCount -lt 1000) { throw "Phased-load runs require -UserCount of at least 1000." }
    if ($DrainTimeoutSeconds -lt 300) { throw "Phased-load runs require -DrainTimeoutSeconds of at least 300." }
    # The warm-up phase's rate, resolved under a name that is not the parameter: variable names are
    # case-insensitive on this PowerShell, so a local named like the -WarmupTargetRps parameter is
    # the parameter itself, and the earlier form of this block assigned the default over the caller's
    # value before testing it - the dry run printed the warm-up at the measured rate while looking
    # correct. The resolved rate therefore lives in $warmupPhaseRps, which is not the parameter.
    # Assigned in a branch rather than as `$x = if (...) {...} else {...}`, because an if used as an
    # expression unrolls its output.
    if ($WarmupTargetRps -lt 0) { throw "-WarmupTargetRps must not be negative; 0 means the warm-up is offered -TargetRps." }
    $warmupPhaseRps = [double]$TargetRps
    if ($WarmupTargetRps -gt 0) { $warmupPhaseRps = [double]$WarmupTargetRps }
    # Its population comes out of the same user pool, and a warm-up above the measured rate needs more
    # users than -TargetRps alone asks for. Checked here because the failure would otherwise be a
    # warm-up phase that quietly offers less than its rate.
    $warmupPopulation = [int][math]::Max(1, [math]::Ceiling($warmupPhaseRps * 3100 / 1000))
    if ($UserCount -lt $warmupPopulation) { throw "UserCount must be at least $warmupPopulation for the warm-up phase's 3100ms per-user pace." }
    $measurementPopulation = [int][math]::Max(1, [math]::Ceiling($TargetRps * 3100 / 1000))
    if ($AuthPrepSeconds -gt 0) {
        if ($AuthPrepSeconds -ne $RampSeconds) {
            throw "-AuthPrepSeconds ($AuthPrepSeconds) must equal -RampSeconds ($RampSeconds): the sessions are prepared over the ramp and the submissions are released at its end, so the two names are one window."
        }
        if ($AuthPrepSeconds -lt 10) {
            throw "-AuthPrepSeconds must be at least 10 so a population of thousands is spread over it rather than arriving together."
        }
        # The login feeder is a non-circular list of one account per session by design - a recycled
        # account would be a second live session sharing that account's rate-limit and dedup state,
        # so the load would not be the load it claims to be - which means every session replacement
        # the closed model makes costs another record. The 2026-09-20 burst runs died exactly there:
        # refusals replaced sessions, the replacements consumed the pool sized for the peak alone,
        # and the feeder emptied before a single submission was persisted. Requiring room for one
        # full replacement of the population is what keeps that a margin rather than a cliff.
        if ($UserCount -lt 2 * $measurementPopulation) {
            throw "UserCount must be at least $(2 * $measurementPopulation) when -AuthPrepSeconds separates login from submission: the peak population is $measurementPopulation, and a non-circular feeder sized for the peak alone is emptied by the session replacements a refusal causes."
        }
    }
    if ($IngressPreflight) {
        if ($AuthPrepSeconds -le 0) { throw "-IngressPreflight exists to rule out a login storm at the measurement start, so it requires -AuthPrepSeconds." }
        if ($PreflightHoldSeconds -lt 1) { throw "-PreflightHoldSeconds must be at least 1." }
        if ($PreflightReadinessTimeoutSeconds -lt 1) { throw "-PreflightReadinessTimeoutSeconds must be at least 1." }
        if ($PreflightProbeUserIndex -lt 1 -or $PreflightProbeUserIndex -gt $UserCount) { throw "-PreflightProbeUserIndex must name a seeded user (1..$UserCount)." }
    }
    # Each phase is its own Gatling invocation with one stage, so warmupStageCount is 0: the phase
    # boundary is the harness draining the pipeline between the two runs, not a warm-up stage inside
    # one schedule. The hold carries the steady guard on top of the measured window, so the window
    # the percentiles are read over is exactly -MeasurementSeconds long.
    $stageRpsList = @([double]$TargetRps)
    $WarmupStageCount = 0
    $measurementHoldSeconds = $MeasurementSeconds + $SteadyGuardSeconds
    # Both contests are seeded from the seed alone, so a rerun of the same setting reseeds the same
    # users and therefore the same code strings: the 5% slow-job split is keyed on the code, and an
    # identical code set is what makes the synthetic judge work reproducible across runs. The prefixes
    # keep the two rounds' contests apart even when a rerun lands in the same database.
    if ($FaultRecovery) {
        $warmupPrefix = "fault_warm_$LatencySeed"
        $measurementPrefix = "fault_meas_$LatencySeed"
        $preflightPrefix = "fault_pre_$LatencySeed"
    } else {
        $warmupPrefix = "norm_warm_$LatencySeed"
        $measurementPrefix = "norm_meas_$LatencySeed"
        $preflightPrefix = "norm_pre_$LatencySeed"
    }
}
if ($FaultRecovery) {
    if ($DownDurationSeconds -le 0) { throw "-FaultRecovery requires a positive -DownDurationSeconds: it is the outage the recovery is measured across." }
    # 30s, not 12: the pre-fault steady baseline this experiment reports is fixed at faultInjectedAt -
    # 30s to faultInjectedAt - 5s, and the analyzer baselines that same window. A shorter trigger window
    # would leave the fault landing before the baseline it is compared against exists.
    if ($FaultMinSteadySeconds -lt 30) { throw "-FaultMinSteadySeconds must be at least 30: the pre-fault steady baseline is the 30s before the fault, and the analyzer baselines the same window." }
    if ($FaultTriggerWaitSeconds -lt 1) { throw "-FaultTriggerWaitSeconds must be positive." }
    if ($FaultMinRunning -lt 1 -or $FaultMinReserved -lt 1) { throw "The fault trigger needs active work, so -FaultMinRunning and -FaultMinReserved must be at least 1." }
    if ($FaultFallbackMinReserved -lt 1 -or $FaultFallbackMinReserved -ge $FaultMinReserved) {
        throw "-FaultFallbackMinReserved must be at least 1 and strictly below -FaultMinReserved, or it is not a fallback."
    }
    # The measured hold has to outlast the fault, the outage and the recovery it is measuring. A hold
    # that ends first truncates the post-recovery observation, and a truncated window is not a
    # recovery measurement - so it is refused here rather than discovered in the analysis.
    $faultWorstCaseSeconds = $FaultMinSteadySeconds + $FaultTriggerWaitSeconds + $DownDurationSeconds
    if ($MeasurementSeconds -lt ($faultWorstCaseSeconds + 45)) {
        throw "-MeasurementSeconds must be at least $($faultWorstCaseSeconds + 45) for this trigger: the window opens at ${FaultMinSteadySeconds}s, waits up to ${FaultTriggerWaitSeconds}s, the outage lasts ${DownDurationSeconds}s, and at least 45s must remain for readiness plus the post-recovery steady window."
    }
}
$effectiveHoldSeconds = if ($phasedLoad) { $measurementHoldSeconds } else { $StageHoldSeconds }
$workloadPrefix = if ($phasedLoad) { $measurementPrefix } else { "tradeoff_seed_$LatencySeed" }

# Whether a fault was actually injected, as opposed to merely requested. This is not the same as the
# mode: a recovery run can reach the end of its trigger window and be marked as injected without
# active work, and it can in principle fail before injecting at all. The counter recombination in
# Get-PromMetricDelta needs a "pre-fault" snapshot to exist, so it keys on this flag; a mode-keyed
# test would subtract a baseline that was never taken and silently drop the killed node's whole
# pre-kill contribution.
$faultWasInjected = [bool]$FaultEnabled

# The staircase drives the stack into overload on purpose, where the API rate limiter refuses
# requests the judge never saw. Those refusals are part of the measurement, not a fault, so the
# staircase default is looser than the fault experiments' 95%; an explicit value still wins, and
# Gatling exiting 2 over it is recorded rather than treated as a failed run.
$assertMinSuccess = if ($Staircase) {
    if ($PSBoundParameters.ContainsKey("AssertMinSuccessPercent")) { $AssertMinSuccessPercent } else { 80 }
} elseif ($phasedLoad) {
    # A phased-load run is offered about 70% of the measured knee, so it stays well inside the
    # rate limiter and the staircase's looser bound is not needed; an explicit value still wins. A
    # judge outage never reaches the client as an API failure - the web node persists the submission
    # and answers 202 - so losing one judge node must not move this bound.
    if ($PSBoundParameters.ContainsKey("AssertMinSuccessPercent")) { $AssertMinSuccessPercent } else { 95 }
} else { 95 }

$neededUsers = if ($Staircase) {
    [int][math]::Ceiling(($stageRpsList | Measure-Object -Maximum).Maximum * 3.1)
} else {
    [int][math]::Ceiling($TargetRps * 3.1)
}
if ($UserCount -lt $neededUsers) { throw "UserCount must be at least $neededUsers for the 3100ms per-user pace." }

# What the harness expects the JVM to plan, derived from the parameters alone. The authoritative
# boundaries are the ones the simulation writes to the trace file; this is here so a dry run can
# be checked against the intended shape before a four minute stack is started for real.
$expectedPlan = $null
if ($Staircase) {
    $populations = @($stageRpsList | ForEach-Object { [int][math]::Max(1, [math]::Ceiling($_ * 3100 / 1000)) })
    $expectedPlan = [ordered]@{
        populations = $populations
        maxConcurrentUsers = ($populations | Measure-Object -Maximum).Maximum
        segmentCount = 1 + $stageRpsList.Count + ($stageRpsList.Count - 1)
        totalSeconds = ($RampSeconds + $StageHoldSeconds) * $stageRpsList.Count
        measuredStageCount = $stageRpsList.Count - $WarmupStageCount
    }
} elseif ($phasedLoad) {
    $population = [int][math]::Max(1, [math]::Ceiling($TargetRps * 3100 / 1000))
    $expectedPlan = [ordered]@{
        targetRps = $TargetRps
        population = $population
        warmupPhase = [ordered]@{
            contestPrefix = $warmupPrefix; rampSeconds = $RampSeconds
            authPrepSeconds = $AuthPrepSeconds
            targetRps = $warmupPhaseRps; population = $warmupPopulation
            holdSeconds = $WarmupSeconds; seconds = $RampSeconds + $WarmupSeconds
        }
        measurementPhase = [ordered]@{
            contestPrefix = $measurementPrefix; rampSeconds = $RampSeconds
            authPrepSeconds = $AuthPrepSeconds
            holdSeconds = $measurementHoldSeconds; steadyGuardSeconds = $SteadyGuardSeconds
            measuredWindowSeconds = $MeasurementSeconds; seconds = $RampSeconds + $measurementHoldSeconds
        }
        # With the separation off this is the older reading of the same numbers and nothing moved:
        # the ramp both starts the users and carries their first submissions. With it on, the ramp
        # is the preparation window and the offered load starts at its end, so the window the
        # harness reads (hold start + guard) opens after the burst has already begun.
        submissionOnset = if ($AuthPrepSeconds -gt 0) {
            "released at anchor + rampSeconds, the traced ramp/hold boundary: the ramp is the session-preparation window and carries logins only"
        } else {
            "each user begins submitting as it logs in, one full submitIntervalMillis of initial jitter from the ramp"
        }
        totalSeconds = 2 * $RampSeconds + $WarmupSeconds + $measurementHoldSeconds
    }
    if ($FaultRecovery) {
        # There is no kill deadline: the trigger window opens after the pre-fault steady stretch and
        # the kill happens the moment the target node is observed to hold work. Worst case is the whole
        # window elapsing, which is what the measurement hold has to cover with room to spare.
        $expectedPlan.faultRecovery = [ordered]@{
            killedNode = $KilledNode
            triggerWindowOpensSecondsAfterMeasurementStart = $FaultMinSteadySeconds
            triggerWaitSeconds = $FaultTriggerWaitSeconds
            primaryCondition = "running >= $FaultMinRunning and reserved >= $FaultMinReserved"
            fallbackCondition = "running >= $FaultMinRunning and reserved >= $FaultFallbackMinReserved after the window elapses"
            downDurationSeconds = $DownDurationSeconds
            worstCaseFaultInjectedSeconds = $FaultMinSteadySeconds + $FaultTriggerWaitSeconds
            worstCaseRestartRequestedSeconds = $FaultMinSteadySeconds + $FaultTriggerWaitSeconds + $DownDurationSeconds
            secondsLeftAfterWorstCaseRestart = $MeasurementSeconds - ($FaultMinSteadySeconds + $FaultTriggerWaitSeconds + $DownDurationSeconds)
        }
    }
}

$resultsRoot = Join-Path $repoRoot "results\mysql-judge-tradeoff"
$runDirectory = Join-Path $resultsRoot $RunId
if (Test-Path $runDirectory) { throw "Run directory already exists: $runDirectory" }
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $runDirectory "metrics") | Out-Null

$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
# The commit alone cannot say whether the code that ran was the code at that commit. The harness is a
# file in the working tree, so a fix can be carried uncommitted across a whole matrix - which is
# exactly how the trace-argument fix that unblocked the 2026-09-20 runs was carried, leaving six runs
# that record a commit whose harness cannot reproduce them. These two fields make a run's own
# provenance checkable afterwards: the hash names the revision that actually executed, and the dirty
# flag says whether the tree it came from also held uncommitted changes elsewhere.
$harnessHash = (Get-FileHash -Algorithm SHA256 -Path $PSCommandPath).Hash
$harnessDirty = @(& git -C $repoRoot status --porcelain -- scripts/mysql-judge-tradeoff).Count -gt 0
$gitTreeDirty = @(& git -C $repoRoot status --porcelain).Count -gt 0
$analyzerPath = Join-Path $PSScriptRoot "Analyze-TradeoffRun.ps1"
$analyzerHash = (Get-FileHash -Algorithm SHA256 -Path $analyzerPath).Hash
$parameters = [ordered]@{
    runId = $RunId; gitCommit = $gitCommit
    gitTreeDirty = $gitTreeDirty
    harnessScriptSha256 = $harnessHash; harnessTreeDirty = $harnessDirty
    analyzerScriptSha256 = $analyzerHash
    dispatchMode = $DispatchMode
    targetRps = $TargetRps; durationSeconds = $DurationSeconds; rampSeconds = $RampSeconds
    workerCountPerNode = $WorkerCount; mysqlClaimBatchSize = $MySqlClaimBatchSize
    mysqlMaxInFlightPerNode = $MySqlMaxInFlight; mysqlClaimTimeout = $MySqlClaimTimeout
    mysqlClaimTimeoutProperty = $claimTimeoutProperty
    mysqlPollInterval = $MySqlPollInterval; rabbitPrefetch = $RabbitPrefetch
    rabbitReservedPerNode = $WorkerCount * $RabbitPrefetch
    deterministicLatencySeed = $LatencySeed; latency = @{ baseMillis = 50; slowMillis = 2000; slowRatio = 0.05; keySource = "code" }
    # Three different things can put a run's fault fields in play, and they are not interchangeable:
    # the legacy fixed-time fault kills at a scheduled second, the recovery mode kills when the target
    # node is observed to hold work, and a run with neither has no fault at all. Downstream readers key
    # on this rather than on faultEnabled, which is true for both of the first two.
    faultEnabled = [bool]$FaultEnabled; faultAtSeconds = $FaultAtSeconds
    faultMode = if ($FaultEnabled) { "fixed-time" } elseif ($FaultRecovery) { "conditional-recovery" } else { "none" }
    killedNode = $KilledNode; downDurationSeconds = $DownDurationSeconds
    userCount = $UserCount; drainTimeoutSeconds = $DrainTimeoutSeconds
    judgeNodeCount = 2; generatedAt = [datetimeoffset]::UtcNow.ToString("o")
}
if ($Staircase) {
    $parameters.staircase = [ordered]@{
        enabled = $true
        stageRps = $stageRpsList
        warmupStageCount = $WarmupStageCount
        transitionRampSeconds = $RampSeconds
        stageHoldSeconds = $StageHoldSeconds
        steadyGuardSeconds = $SteadyGuardSeconds
        overloadThresholdRowsPerSec = $OverloadThresholdRowsPerSec
        assertMinSuccessPercent = $assertMinSuccess
        assertP95Millis = $AssertP95Millis
        traceAlignmentToleranceSeconds = $TraceAlignmentToleranceSeconds
        simulationClass = "my.oj.perf.ContestSubmissionStepLoadSimulation"
        stageTraceFile = "stage-trace.csv"
        expectedPlan = $expectedPlan
    }
}
if ($NormalTimeout) {
    $parameters.normalTimeout = [ordered]@{
        enabled = $true
        targetRps = $TargetRps
        warmupTargetRps = $warmupPhaseRps
        warmupSeconds = $WarmupSeconds
        measurementSeconds = $MeasurementSeconds
        measurementHoldSeconds = $measurementHoldSeconds
        steadyGuardSeconds = $SteadyGuardSeconds
        warmupPrefix = $warmupPrefix
        measurementPrefix = $measurementPrefix
        simulationClass = "my.oj.perf.ContestSubmissionStepLoadSimulation"
        # One stage per Gatling invocation, so the measured window is labelled stage-0 in
        # timeseries.csv and stages.json rather than "measurement".
        measuredStageLabel = "stage-0"
        measurementWindowBasis = "the hold minus steadyGuardSeconds; the preceding ramp is part of the schedule but outside every measured window"
        authPrepSeconds = $AuthPrepSeconds
        sessionPreparation = if ($AuthPrepSeconds -gt 0) {
            "login is separated from submission: the ramp is the preparation window over which the population's sessions are established, and no submission is sent until it closes"
        } else {
            "not separated: every user logs in and begins submitting as it starts, so the logins are spread only as widely as the ramp is long"
        }
        ingressPreflight = [bool]$IngressPreflight
        preflightPrefix = if ($IngressPreflight) { $preflightPrefix } else { $null }
        preflightHoldSeconds = if ($IngressPreflight) { $PreflightHoldSeconds } else { $null }
        expectedPlan = $expectedPlan
    }
}
if ($FaultRecovery) {
    $parameters.faultRecovery = [ordered]@{
        enabled = $true
        targetRps = $TargetRps
        warmupSeconds = $WarmupSeconds
        measurementSeconds = $MeasurementSeconds
        measurementHoldSeconds = $measurementHoldSeconds
        steadyGuardSeconds = $SteadyGuardSeconds
        warmupPrefix = $warmupPrefix
        measurementPrefix = $measurementPrefix
        killedNode = $KilledNode
        signal = "SIGKILL"
        # The trigger is a condition on the target node, not an instant. faultScheduledAt therefore
        # means "the moment the trigger window opened", which is measurementStartedAt + this value -
        # it is not a kill deadline, and nothing is killed at it.
        minSteadySecondsBeforeTriggerWindow = $FaultMinSteadySeconds
        triggerWaitSeconds = $FaultTriggerWaitSeconds
        triggerPrimaryMinRunning = $FaultMinRunning
        triggerPrimaryMinReserved = $FaultMinReserved
        triggerFallbackMinReserved = $FaultFallbackMinReserved
        triggerBasis = "poll the target node's executor gauges once the window is open; inject on the primary condition, else on the fallback when the window elapses, else inject anyway and mark the run faultNotInjectedWithActiveWork"
        downDurationSeconds = $DownDurationSeconds
        downDurationBasis = "restartRequestedAt - faultInjectedAt; the restart is scheduled at faultInjectedAt + downDurationSeconds, so a slow pre-kill snapshot shifts the window rather than shrinking it"
        restartSettingsIdentical = $true
        nodeReadyBasis = "container running AND /actuator/health/readiness UP AND /actuator/prometheus scrapable AND contest_judge_claim_calls_total observed to advance; a judge container's own healthcheck is process liveness only and is not used"
        claimedUnfinishedBasis = "the judge schema has no claimed_by column, so the claimed unfinished row count at kill time is a cluster-wide upper bound, not the killed node's active claims"
        attemptsAboveOneBasis = "recovery re-claims, not concurrent duplicate CPU execution; the process that held the claim was SIGKILLed, so it did not keep judging"
        simulationClass = "my.oj.perf.ContestSubmissionStepLoadSimulation"
        measuredStageLabel = "stage-0"
        measurementWindowBasis = "the hold minus steadyGuardSeconds; the preceding ramp is part of the schedule but outside every measured window"
        expectedPlan = $expectedPlan
    }
}
$parameters | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDirectory "parameters.json") -Encoding utf8

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    Push-Location $repoRoot
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        # compose writes build and pull progress to stderr, and PowerShell 5.1 turns a native
        # command's stderr into an ErrorRecord. Under the caller's $ErrorActionPreference = "Stop"
        # that terminated the script on a successful build ("Image oj-loadtest-judge-2 Building"),
        # so stderr goes to a file: callers that parse stdout (Invoke-SqlRows, the dry run's
        # `config`) stay clean, the exit code alone decides success, and a real failure still
        # carries compose's own message.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & docker compose @composeArgs @Arguments 2>$stderrFile
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousPreference }
        if ($exitCode -ne 0) {
            $detail = (Get-Content $stderrFile -Raw -ErrorAction SilentlyContinue)
            throw "docker compose failed (exit $exitCode): $($Arguments -join ' ')`n$detail"
        }
    } finally {
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue
        Pop-Location
    }
}

function Invoke-SqlRows {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $oneLine = ($Sql -replace "\r?\n", " ").Trim()
    $output = @(Invoke-Compose -Arguments @("exec", "-T", "mysql", "env", "MYSQL_PWD=1234", "mysql", "-uroot", "-D", $dbName, "-N", "-B", "-e", $oneLine))
    return @($output | ForEach-Object { [string]$_ } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch '^(Container|Network|mysql:) ' })
}

function Get-SqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $rows = @(Invoke-SqlRows $Sql)
    # An empty result is not a zero. A COUNT(*) that returns no row means the query did not run,
    # and reporting 0 for it would let the four integrity counts agree on all-zero and pass a run
    # whose verification never happened, or let the drain gate see "0 unfinished" and break early.
    if ($rows.Count -eq 0) { return $null }
    $value = 0L
    if (-not [long]::TryParse([string]$rows[-1], [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { return $null }
    return $value
}

function ConvertTo-Int64OrNull {
    param($Value)
    # A count that did not arrive is not a zero. `[long]$null` and `[long]""` are both 0, so casting a
    # missing cell would let the staircase drain gate read 0 unfinished rows from a tick whose SQL
    # never ran, break on its first iteration, and record the run as drained.
    if ($null -eq $Value) { return $null }
    $parsed = 0L
    if (-not [long]::TryParse([string]$Value, [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) { return $null }
    return $parsed
}

function ConvertTo-DoubleOrNull {
    param($Value)
    # Micrometer renders an executor gauge as "16.0": a decimal point on an integer-valued metric.
    # Parsing that with Integer styles returns null, and a null gauge means "this node could not be
    # read" everywhere below - so an integer-only parse silently turns a busy node into an
    # unreachable one. Float styles accept both spellings and keep the absent-is-not-zero rule.
    if ($null -eq $Value) { return $null }
    $parsed = 0.0
    if (-not [double]::TryParse([string]$Value, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) { return $null }
    return $parsed
}

function Get-ResponseText {
    param([Parameter(Mandatory = $true)]$Response)
    # Windows PowerShell 5.1 hands back Invoke-WebRequest's Content as a byte array whenever the
    # response media type is not text/*, and Spring's actuator media type
    # (application/vnd.spring-boot.actuator.v3+json) is one of those. ConvertFrom-Json over those
    # bytes yields a document with no properties instead of throwing, so a readiness probe reads
    # "answered, but the document has no status" on every attempt and burns its entire timeout while
    # the endpoint is answering 200 UP. /actuator/prometheus escaped this only because it is
    # text/plain. Decoding here makes the gate independent of the media type the server chose.
    if ($null -eq $Response) { return "" }
    if ($Response.Content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($Response.Content) }
    return [string]$Response.Content
}

function Wait-Healthy {
    param([switch]$ObserveRecovery)
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        if ($ObserveRecovery) { Observe-FaultRecovery "health-wait" }
        $ids = @(Invoke-Compose -Arguments @("ps", "-q") | Where-Object { $_ })
        if ($ids.Count -eq 9) {
            $bad = @(& docker inspect --format '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $ids | Where-Object { $_ -notmatch '^true (healthy|none)$' })
            if ($LASTEXITCODE -eq 0 -and $bad.Count -eq 0) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw "Load-test stack did not become healthy in five minutes."
}

function Wait-JudgeMetrics {
    param(
        [Parameter(Mandatory = $true)][string]$Node,
        [switch]$ObserveRecovery,
        [scriptblock]$OnPoll = $null
    )
    $port = if ($Node -eq "judge-1") { 19001 } else { 19002 }
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        # Observation is offered to the caller on every poll rather than owning the wait. A fault run
        # needs its 1s series to continue across the restart, and the deadline this loop answers to is
        # checked before the poll, so a slow sample can only delay the next check, never move the clock.
        if ($null -ne $OnPoll) { & $OnPoll }
        if ($ObserveRecovery) {
            Observe-FaultRecovery "restart-wait"
        }
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$port/actuator/prometheus" | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "$Node metrics endpoint did not become available within 60 seconds."
}

function Save-MetricsSnapshot {
    param([Parameter(Mandatory = $true)][string]$Label)
    foreach ($entry in @(@("batch-1", 19000), @("judge-1", 19001), @("judge-2", 19002))) {
        $path = Join-Path $runDirectory "metrics\$Label-$($entry[0]).prom"
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$($entry[1])/actuator/prometheus"
            Get-ResponseText -Response $response | Set-Content $path -Encoding utf8
        } catch {
            "# unavailable: $($_.Exception.Message)" | Set-Content $path -Encoding utf8
        }
    }
    $status = Invoke-SqlRows "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Questions','Com_select','Com_update');"
    @("metric`tvalue") + $status | Set-Content (Join-Path $runDirectory "metrics\$Label-mysql-status.tsv") -Encoding utf8
}

function Get-JudgeGauges {
    param([Parameter(Mandatory = $true)][int]$Port)
    $values = @{ running = ""; queued = ""; reserved = "" }
    try {
        $content = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$Port/actuator/prometheus").Content
        foreach ($metric in @("running", "queued", "reserved")) {
            $match = [regex]::Match($content, "(?m)^contest_judge_executor_$metric(?:\{[^}]*\})?\s+([^\s]+)$")
            if ($match.Success) { $values[$metric] = $match.Groups[1].Value }
        }
    } catch {
        # An unreachable node is recorded as an empty value, never as zero.
        $values = @{ running = ""; queued = ""; reserved = "" }
    }
    return $values
}

function Add-SampleRow {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Row,
        [int]$Attempts = 12,
        [int]$RetryMilliseconds = 250
    )
    # The samplers append one row a second for the whole measured hold, and a single failed append used
    # to end the run. A measurement is minutes long, so losing one to a transient reader that holds the
    # file for a moment is the wrong trade: the second smoke run died at its 28th sample with
    # "being used by another process" on this exact call, and the reader was the operator's own
    # diagnostic read of the live file, not anything in the system under test.
    #
    # Retrying does not hide a failure. The wait is bounded, every retry is counted, the count is
    # reported in events.json, and exhausting the attempts still throws with the last error - so a row
    # that could not be written is a failed run exactly as before, and a row that was written a quarter
    # of a second late is visible as a retry rather than silently absorbed.
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Add-Content -Path $Path -Value $Row -Encoding utf8 -ErrorAction Stop
            if ($attempt -gt 1) {
                $script:sampleWriteRetries++
                # A hashtable member, so the write lands on the object the run serializes and not on a
                # copy of it in this function's scope.
                $events.sampleWriteRetries = $script:sampleWriteRetries
            }
            return
        } catch {
            if ($attempt -eq $Attempts) { throw }
            Start-Sleep -Milliseconds $RetryMilliseconds
        }
    }
}

function Save-CapacitySample {
    param([Parameter(Mandatory = $true)][string]$Phase)
    $path = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $path)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $path -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", 19001), @("judge-2", 19002))) {
        $values = Get-JudgeGauges -Port $entry[1]
        Add-SampleRow -Path $path -Row "$([datetimeoffset]::UtcNow.ToString('o')),$Phase,$($entry[0]),$($values.running),$($values.queued),$($values.reserved)"
    }
}

function Save-BacklogSample {
    param([string]$Phase)
    $pending = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
    $unapplied = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE scoreboard_applied_at IS NULL"
    $path = Join-Path $runDirectory "backlog.csv"
    if (-not (Test-Path $path)) { "timestamp,phase,unfinished,scoreboardUnapplied" | Set-Content $path -Encoding utf8 }
    # An unavailable count is written as an empty cell rather than a zero, and the sample returns
    # null so a caller cannot read "0" as a drained pipeline.
    $pendingText = if ($null -eq $pending) { "" } else { [string]$pending }
    $unappliedText = if ($null -eq $unapplied) { "" } else { [string]$unapplied }
    Add-SampleRow -Path $path -Row "$( [datetimeoffset]::UtcNow.ToString('o')),$Phase,$pendingText,$unappliedText"
    if ($null -eq $pending -or $null -eq $unapplied) { return $null }
    return ($pending + $unapplied)
}

function Observe-FaultRecovery {
    param([string]$Phase)
    Save-BacklogSample $Phase | Out-Null
    if ($null -eq $events.firstStaleReclaimObservedAt -and $events.contestId) {
        $reclaimed = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(o.attempts - 1, 0)), 0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.contestId)"
        if ($reclaimed -gt $events.staleAttemptsBeforeFault) {
            # attempts is durable after the reclaim completes, unlike claimed_at/updated_at. Polling
            # once a second gives an explicit bounded observation error instead of pretending the
            # final row update timestamp is the instant at which the stale lease was acquired.
            $events.firstStaleReclaimObservedAt = [datetimeoffset]::UtcNow.ToString("o")
        }
    }
}

function Save-ClaimSnapshot {
    $columns = @(Invoke-SqlRows "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema = '$dbName' AND table_name = 'contest_judge_outbox';")
    $hasClaimedBy = $columns -contains "claimed_by"
    $filter = "status = 'PUBLISHING'"
    if ($hasClaimedBy) {
        $workerId = if ($KilledNode -eq "judge-1") { "200" } else { "201" }
        $filter += " AND claimed_by IN ('$KilledNode', '$workerId')"
    }
    $rows = Invoke-SqlRows "SELECT submission_id, claim_token, claimed_at, attempts FROM contest_judge_outbox WHERE $filter ORDER BY submission_id;"
    $objects = @($rows | ForEach-Object {
        $p = $_ -split "`t"; [pscustomobject]@{ submissionId=$p[0]; claimToken=$p[1]; claimedAt=$p[2]; attempts=$p[3] }
    })
    $objects | Export-Csv (Join-Path $runDirectory "killed-node-claims.csv") -NoTypeInformation -Encoding utf8
    return [pscustomobject]@{
        exact = $hasClaimedBy
        # Never label the all-active upper bound as the killed node's exact cohort.
        ids = if ($hasClaimedBy) { @($objects.submissionId) } else { @() }
        observedActiveClaimCount = @($objects).Count
        # The raw rows are handed back so the kill snapshot can report claimed_at ages without a
        # second query against a table that is only frozen after the kill.
        rows = $objects
    }
}

function Export-Latencies {
    param($Events, $ClaimSnapshot)
    $rows = Invoke-SqlRows @"
SELECT cs.id, cs.submitted_time, csr.result_saved_at, csr.scoreboard_applied_at,
       TIMESTAMPDIFF(MICROSECOND, cs.submitted_time, csr.result_saved_at) / 1000.0,
       TIMESTAMPDIFF(MICROSECOND, csr.result_saved_at, csr.scoreboard_applied_at) / 1000.0,
       TIMESTAMPDIFF(MICROSECOND, cs.submitted_time, csr.scoreboard_applied_at) / 1000.0,
       o.attempts, o.updated_at, HEX(cs.code)
FROM contest_submission cs
LEFT JOIN contest_submission_result csr ON csr.submission_id = cs.id
LEFT JOIN contest_judge_outbox o ON o.submission_id = cs.id
WHERE cs.contest_id = $($Events.contestId)
ORDER BY cs.id;
"@
    $fault = if ($Events.faultInjectedAt) { [datetimeoffset]::Parse($Events.faultInjectedAt) } else { $null }
    $restart = if ($Events.nodeRestartedAt) { [datetimeoffset]::Parse($Events.nodeRestartedAt) } else { $null }
    $claimed = @{}; foreach ($id in @($ClaimSnapshot.ids)) { $claimed[[string]$id] = $true }
    $objects = foreach ($line in $rows) {
        # PowerShell's regex -split treats a negative count as "do not split".
        # String.Split preserves the tabular fields emitted by mysql -B, including NULL markers.
        $p = $line.Split([char]9)
        $submitted = [datetimeoffset]::MinValue
        if ($p.Count -lt 10 -or -not [datetimeoffset]::TryParse($p[1] + "Z", [ref]$submitted)) {
            continue
        }
        $codeBytes = New-Object byte[] ($p[9].Length / 2)
        for ($index = 0; $index -lt $codeBytes.Length; $index++) {
            $codeBytes[$index] = [Convert]::ToByte($p[9].Substring($index * 2, 2), 16)
        }
        $code = [Text.Encoding]::UTF8.GetString($codeBytes)
        $latencyClass = if ([MysqlJudgeTradeoff.LatencyClassifier]::IsSlow($LatencySeed, $code, 0.05)) { "slow" } else { "fast" }
        $cohorts = New-Object System.Collections.Generic.List[string]
        if ($fault) {
            if ($submitted -lt $fault.AddSeconds(-5)) { $cohorts.Add("pre-fault-normal") }
            if ($submitted -ge $fault.AddSeconds(-5) -and ($null -eq $restart -or $submitted -lt $restart.AddSeconds(5))) { $cohorts.Add("fault-window") }
            if ($submitted -ge $fault) { $cohorts.Add("post-fault-arrivals") }
        } else { $cohorts.Add("pre-fault-normal") }
        if ($claimed.ContainsKey($p[0])) { $cohorts.Add("killed-node-claimed") }
        [pscustomobject]@{
            submissionId=$p[0]; submittedAt=$p[1]; resultSavedAt=$p[2]; scoreboardAppliedAt=$p[3]
            L_result_ms=$p[4]; L_scoreboard_ms=$p[5]; L_total_ms=$p[6]; attempts=$p[7]
            outboxUpdatedAt=$p[8]; latencyClass=$latencyClass; cohorts=($cohorts -join ";")
        }
    }
    $objects | Export-Csv (Join-Path $runDirectory "latency.csv") -NoTypeInformation -Encoding utf8
}

function Find-GatlingReport {
    param([datetime]$StartedAt)
    $logs = @(Get-ChildItem (Join-Path $repoRoot "gatling\build\reports\gatling") -Filter simulation.log -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $StartedAt } | Sort-Object LastWriteTime)
    if ($logs.Count -eq 0) { return $null }
    return $logs[-1]
}

function Get-GatlingSubmitRequests {
    param([datetime]$StartedAt)
    $log = Find-GatlingReport -StartedAt $StartedAt
    if ($null -eq $log) { return $null }
    return [long](@(Select-String -Path $log.FullName -SimpleMatch "api-contest-submit").Count)
}

function Get-GatlingLastRequestMillis {
    param([datetime]$StartedAt)
    $log = Find-GatlingReport -StartedAt $StartedAt
    if ($null -eq $log) { return $null }
    $last = $null
    foreach ($line in [System.IO.File]::ReadLines($log.FullName)) {
        if (-not $line.StartsWith("REQUEST`t")) { continue }
        $p = $line.Split([char]9)
        if ($p.Count -lt 6) { continue }
        $value = 0L
        # A request that never returned carries an empty end timestamp; the last *completed*
        # request is the one that can be compared against the predicted plan.
        if ([long]::TryParse($p[4], [ref]$value) -and ($null -eq $last -or $value -gt $last)) { $last = $value }
    }
    return $last
}

function Copy-GatlingArtifacts {
    param([datetime]$StartedAt, [string]$NamePrefix = "")
    $log = Find-GatlingReport -StartedAt $StartedAt
    if ($null -eq $log) { return $null }
    Copy-Item $log.FullName (Join-Path $runDirectory "$($NamePrefix)gatling-simulation.log") -Force
    $stats = Join-Path $log.Directory.FullName "js\global_stats.json"
    if (Test-Path $stats) { Copy-Item $stats (Join-Path $runDirectory "$($NamePrefix)gatling-global-stats.json") -Force }
    return $log.Directory.FullName
}

# Every request in a Gatling simulation.log, not just the submits. The submit-only reader in the
# analyzer answers "was the offered submission rate refused"; the preflight has to answer a different
# question that the same log holds - were the *logins* refused, and did any request of any name land
# inside the measured window. The status classification is deliberately the analyzer's, character for
# character, so both readers call the same connection refusal by the same name.
function Get-GatlingRequestRows {
    param([Parameter(Mandatory = $true)][string]$Path)
    $rows = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path $Path)) { return $rows }
    foreach ($line in [System.IO.File]::ReadLines($Path)) {
        if (-not $line.StartsWith("REQUEST`t")) { continue }
        $p = $line.Split([char]9)
        if ($p.Count -lt 6) { continue }
        $started = 0L
        if (-not [long]::TryParse($p[3], [ref]$started)) { continue }
        $message = if ($p.Count -ge 7) { $p[6] } else { "" }
        $status = "ok"
        if ($p[5] -ne "OK") {
            $code = ""
            if ($message -match "actually found (\d{3})") { $code = $Matches[1] }
            if ($code) { $status = "ko$code" }
            elseif ($message -match "ConnectException|Connection refused|connect timed out|UnknownHost|No route to host") { $status = "ko-connect" }
            else { $status = "ko-other" }
        }
        $rows.Add([pscustomobject]@{
            name = $p[2]; startMillis = $started; status = $status; message = $message
        })
    }
    return $rows
}

function Get-StatusCount {
    param($Counts, [string]$Key)
    if ($Counts.ContainsKey($Key)) { return [int]$Counts[$Key] }
    return 0
}

# Per second *and per request name*. A per-second total cannot answer whether logins were mixed into
# the measured window - that is a question about a name - and it cannot answer whether the offered
# submission rate was actually supplied - that is a question about the other name. The rows are
# bucketed by the instant the client sent the request, so `offered` is what the client offered rather
# than what came back; offered is the sum of the outcome columns, and no request is counted twice.
function Export-GatlingPerSecond {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $bySecond = @{}
    foreach ($row in $Rows) {
        $second = [long][math]::Floor($row.startMillis / 1000)
        if (-not $bySecond.ContainsKey($second)) { $bySecond[$second] = @{} }
        $names = $bySecond[$second]
        if (-not $names.ContainsKey($row.name)) { $names[$row.name] = @{} }
        $counts = $names[$row.name]
        $counts[$row.status] = 1 + (Get-StatusCount $counts $row.status)
    }
    $secondRows = foreach ($second in @($bySecond.Keys | Sort-Object)) {
        foreach ($name in @($bySecond[$second].Keys | Sort-Object)) {
            $counts = $bySecond[$second][$name]
            $otherKo = 0
            foreach ($key in @($counts.Keys)) {
                if ($key -like "ko*" -and @("ko429", "ko500", "ko503", "ko-connect") -notcontains $key) {
                    $otherKo += (Get-StatusCount $counts $key)
                }
            }
            $ok = Get-StatusCount $counts "ok"
            $ko429 = Get-StatusCount $counts "ko429"
            $ko500 = Get-StatusCount $counts "ko500"
            $ko503 = Get-StatusCount $counts "ko503"
            $koConnect = Get-StatusCount $counts "ko-connect"
            [pscustomobject]@{
                epochSecond = $second
                timestampUtc = [datetimeoffset]::FromUnixTimeSeconds($second).ToString("o")
                request = $name
                offered = $ok + $ko429 + $ko500 + $ko503 + $koConnect + $otherKo
                ok = $ok; ko429 = $ko429; ko500 = $ko500; ko503 = $ko503
                koConnect = $koConnect; koOther = $otherKo
            }
        }
    }
    $ordered = @($secondRows)
    @($ordered) | Export-Csv $Path -NoTypeInformation -Encoding utf8
    return $ordered
}

# The session lines. A session START after the gate is the signature of a replaced session, which is
# what consumed the non-circular login feeder in the 2026-09-20 runs: the request counts alone would
# show the logins that were refused, not the replacements that followed them.
function Get-GatlingSessionRows {
    param([Parameter(Mandatory = $true)][string]$Path)
    $starts = New-Object System.Collections.Generic.List[long]
    $ends = New-Object System.Collections.Generic.List[long]
    if (-not (Test-Path $Path)) { return [pscustomobject]@{ starts = @(); ends = @() } }
    foreach ($line in [System.IO.File]::ReadLines($Path)) {
        if (-not $line.StartsWith("USER`t")) { continue }
        $p = $line.Split([char]9)
        if ($p.Count -lt 4) { continue }
        $at = 0L
        if (-not [long]::TryParse($p[3], [ref]$at)) { continue }
        if ($p[2] -eq "START") { $starts.Add($at) } elseif ($p[2] -eq "END") { $ends.Add($at) }
    }
    return [pscustomobject]@{ starts = $starts; ends = $ends }
}

# Captured with the preference guard docker needs: a native command's stderr becomes an ErrorRecord
# under this script's $ErrorActionPreference = "Stop", and a log read that has nothing to say would
# otherwise terminate the run it was meant to explain.
function Save-ContainerLog {
    param([Parameter(Mandatory = $true)][string]$Service, [Parameter(Mandatory = $true)][string]$Path, [string]$Since = "")
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $id = @(Invoke-Compose -Arguments @("ps", "-q", $Service) | Where-Object { $_ })
        if ($id.Count -eq 0) { "no container for $Service" | Set-Content $Path -Encoding utf8; return }
        # `docker logs` has no --no-color on this engine, and the run that first needed this log
        # learned it the hard way: the capture wrote "unknown flag: --no-color" into the file it was
        # supposed to fill, so the nginx evidence for the refusal it was recorded beside was lost.
        $arguments = @("logs")
        if ($Since) { $arguments += @("--since", $Since) }
        $arguments += $id[0]
        & docker @arguments 2>&1 | ForEach-Object { [string]$_ } | Set-Content $Path -Encoding utf8
    } finally { $ErrorActionPreference = $previousPreference }
}

# The one-shot container reading. `docker compose ps -q` names the nine containers and one inspect
# answers the three questions section 3 asks of them - running, OOM-killed, restarted - in the form
# the failure note for the 2026-09-20 runs already recorded them in.
function Get-ContainerStates {
    $ids = @(Invoke-Compose -Arguments @("ps", "-q") | Where-Object { $_ })
    if ($ids.Count -eq 0) { return @() }
    return @(& docker inspect --format '{{.Name}}|{{.State.Status}}|exit={{.State.ExitCode}}|oom={{.State.OOMKilled}}|restarts={{.RestartCount}}|started={{.State.StartedAt}}' $ids)
}

function Invoke-IngressPreflight {
    param(
        [Parameter(Mandatory = $true)]$Seed,
        [Parameter(Mandatory = $true)][string]$TraceFile
    )
    # Section 3 of this experiment, as code. The ingress is the component that failed the 2026-09-20
    # burst runs, and it failed before the application saw anything: 2,289 of 3,100 simultaneous
    # connections were refused at the published port while nginx logged nothing and every container
    # stayed running. A comparison run cannot distinguish that from a capacity result, so it is not
    # started until the published port has been observed carrying this experiment's own arrival
    # pattern - the whole measurement population logging in across the same preparation window - with
    # no refusal of any kind, no login inside the hold, and a login-plus-submit round trip answering
    # through nginx. Anything else stops the run here, where both conditions are still unspent.
    $report = [ordered]@{
        startedAt = [datetimeoffset]::UtcNow.ToString("o")
        prefix = $preflightPrefix
        contestId = [long]$Seed.contestId
        population = $measurementPopulation
        preparationSeconds = $AuthPrepSeconds
        holdSeconds = $PreflightHoldSeconds
        targetRps = $TargetRps
        publishedPort = $null
        containers = @()
        readiness = @()
        readinessPolls = $null
        readinessUp = $null
        gatlingStartedAt = $null
        gatlingExitedAt = $null
        gatlingExitCode = $null
        gatlingAssertionFailed = $false
        gateUtc = $null
        measurementStartUtc = $null
        measurementEndUtc = $null
        http = $null
        sessions = $null
        probe = $null
        quiescence = $null
        refusalTotal = $null
        refusedBeforeTheApplicationSawAnything = $null
        problems = @()
        verdict = "not-run"
        finishedAt = $null
    }

    $portMapping = (@(Invoke-Compose -Arguments @("port", "nginx", "80")) -join " ").Trim()
    $report.publishedPort = if ($portMapping) { $portMapping } else { "unavailable" }

    $report.containers = @(Get-ContainerStates)

    # Through the published port, which is the only ingress the load may use. Section 3 asks that
    # readiness succeed repeatedly, and "repeatedly" is two claims: that it comes UP at all, and that
    # it stays UP. Sampling three times inside one second answers neither - it cannot tell a stack
    # still settling apart from one that is down, and it calls a single moment a state. So the
    # endpoint is polled until it reports UP, and only then re-probed three times to confirm. On the
    # 2026-09-20 comparison's first attempt the three-sample version stopped the run on three 500s
    # taken 16 seconds after the stack came up, while the same ingress carried 3,100 logins with no
    # refusal four seconds later.
    $readiness = New-Object System.Collections.Generic.List[object]
    $readinessDeadline = (Get-Date).AddSeconds($PreflightReadinessTimeoutSeconds)
    $readinessUp = $false
    while ((Get-Date) -lt $readinessDeadline) {
        $state = Test-NodeReadiness -Port 18080
        $readiness.Add([pscustomobject]@{
            phase = "wait"; attempt = $readiness.Count + 1
            at = [datetimeoffset]::UtcNow.ToString("o")
            up = $state.up; httpStatus = $state.httpStatus; status = $state.status
        })
        if ($state.up) { $readinessUp = $true; break }
        Start-Sleep -Seconds 2
    }
    if ($readinessUp) {
        foreach ($confirm in 1..3) {
            Start-Sleep -Milliseconds 400
            $state = Test-NodeReadiness -Port 18080
            $readiness.Add([pscustomobject]@{
                phase = "confirm"; attempt = $readiness.Count + 1
                at = [datetimeoffset]::UtcNow.ToString("o")
                up = $state.up; httpStatus = $state.httpStatus; status = $state.status
            })
        }
    }
    $report.readiness = @($readiness)
    $report.readinessPolls = $readiness.Count
    $report.readinessUp = $readinessUp
    $readinessConfirmations = @($readiness | Where-Object { $_.phase -eq "confirm" })
    $readinessFailedConfirmations = @($readinessConfirmations | Where-Object { -not $_.up })

    $report.gatlingStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    $phase = Start-GatlingLoadPhase -PhaseName "preflight" -Seed $Seed -UserPrefix $preflightPrefix `
        -Rps $TargetRps -HoldSeconds $PreflightHoldSeconds -TracePath ($TraceFile -replace '\\', '/')
    $script:preflightPhaseStartedAt = $phase.startedAt
    $phase.process.WaitForExit()
    $report.gatlingExitedAt = [datetimeoffset]::UtcNow.ToString("o")
    $report.gatlingExitCode = $phase.process.ExitCode

    $trace = Get-StaircaseTrace -Path $TraceFile
    $stages = if ($null -ne $trace) { @(Get-StaircaseStages -Trace $trace) } else { @() }
    if ($stages.Count -gt 0) {
        # The gate is the traced ramp/hold boundary: the instant the simulation released the first
        # submission, which is also the start of the hold segment.
        $report.gateUtc = [datetimeoffset]::FromUnixTimeMilliseconds($stages[0].startMillis).ToString("o")
        $report.measurementStartUtc = [datetimeoffset]::FromUnixTimeMilliseconds($stages[0].measurementStartMillis).ToString("o")
        $report.measurementEndUtc = [datetimeoffset]::FromUnixTimeMilliseconds($stages[0].measurementEndMillis).ToString("o")
    }

    Copy-GatlingArtifacts -StartedAt $phase.startedAt -NamePrefix "preflight-" | Out-Null
    $logPath = Join-Path $runDirectory "preflight-gatling-simulation.log"
    $requestRows = Get-GatlingRequestRows -Path $logPath
    Export-GatlingPerSecond -Rows $requestRows -Path (Join-Path $runDirectory "preflight-requests-1s.csv") | Out-Null
    $loginRows = @($requestRows | Where-Object { $_.name -eq "api-login-once" })
    $submitRows = @($requestRows | Where-Object { $_.name -eq "api-contest-submit" })
    $refusals = @($requestRows | Where-Object { $_.status -eq "ko-connect" })
    $refusedLogins = @($loginRows | Where-Object { $_.status -eq "ko-connect" })
    $refusedSubmits = @($submitRows | Where-Object { $_.status -eq "ko-connect" })

    $report.refusalTotal = $refusals.Count
    # The distinction the 2026-09-20 evidence turns on: a refusal before the application saw anything
    # is not the same failure as a refusal of a request the application answered. A refused login is
    # the first kind - it never reached a web node - and it is the one that triggered session
    # replacement and feeder exhaustion.
    $report.refusedBeforeTheApplicationSawAnything = $refusedLogins.Count

    $loginOk = @($loginRows | Where-Object { $_.status -eq "ok" }).Count
    $submitOk = @($submitRows | Where-Object { $_.status -eq "ok" }).Count
    $submitKo429 = @($submitRows | Where-Object { $_.status -eq "ko429" }).Count
    $submitKo500 = @($submitRows | Where-Object { $_.status -eq "ko500" }).Count
    $submitKo503 = @($submitRows | Where-Object { $_.status -eq "ko503" }).Count
    $submitKoOther = @($submitRows | Where-Object { $_.status -eq "ko-other" }).Count
    $report.http = [ordered]@{
        loginOffered = $loginRows.Count
        loginOk = $loginOk
        loginKoConnect = $refusedLogins.Count
        # A login answered with something other than 200 - a 401 from a rejected session, a 503 from
        # the admission limiter - is neither OK nor a refusal, and it is what remains.
        loginKoOther = $loginRows.Count - $loginOk - $refusedLogins.Count
        submitOffered = $submitRows.Count
        submitOk = $submitOk
        submitKoConnect = $refusedSubmits.Count
        submitKo429 = $submitKo429
        submitKo500 = $submitKo500
        submitKo503 = $submitKo503
        submitKoOther = $submitKoOther
    }

    $sessionRows = Get-GatlingSessionRows -Path $logPath
    $gateMillis = if ($stages.Count -gt 0) { [long]$stages[0].startMillis } else { $null }
    $sessionsStartedAfterGate = if ($null -eq $gateMillis) { $null } else {
        @($sessionRows.starts | Where-Object { $_ -ge $gateMillis }).Count
    }
    $loginsInHold = if ($null -eq $gateMillis -or $stages.Count -eq 0) { $null } else {
        @($loginRows | Where-Object { $_.startMillis -ge $gateMillis -and $_.startMillis -lt [long]$stages[0].endMillis }).Count
    }
    $loginsInMeasurementWindow = if ($stages.Count -eq 0) { $null } else {
        @($loginRows | Where-Object {
            $_.startMillis -ge [long]$stages[0].measurementStartMillis -and $_.startMillis -lt [long]$stages[0].measurementEndMillis
        }).Count
    }
    $report.sessions = [ordered]@{
        started = $sessionRows.starts.Count
        ended = $sessionRows.ends.Count
        startedAfterGate = $sessionsStartedAfterGate
        loginsInHold = $loginsInHold
        loginsInMeasurementWindow = $loginsInMeasurementWindow
        firstLoginUtc = if ($loginRows.Count -gt 0) { [datetimeoffset]::FromUnixTimeMilliseconds(($loginRows | Measure-Object -Property startMillis -Minimum).Minimum).ToString("o") } else { $null }
        lastLoginUtc = if ($loginRows.Count -gt 0) { [datetimeoffset]::FromUnixTimeMilliseconds(($loginRows | Measure-Object -Property startMillis -Maximum).Maximum).ToString("o") } else { $null }
    }

    # One login and one submission through the published port, on a seeded account, immediately
    # before the comparison: the readiness group says the web node is up, and this says a session
    # established through nginx is accepted by the submission endpoint.
    $probe = [ordered]@{
        at = [datetimeoffset]::UtcNow.ToString("o")
        userName = "${preflightPrefix}_user_$PreflightProbeUserIndex"
        loginStatus = $null; sessionCookieNames = $null; submissionStatus = $null
        submissionBody = $null; error = $null
    }
    try {
        $webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $login = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/api/login" -ContentType "application/json" `
            -Body (ConvertTo-Json @{ userName = $probe.userName; pass = "pass" } -Compress) -WebSession $webSession -TimeoutSec 15
        $probe.loginStatus = [int]$login.StatusCode
        $probe.sessionCookieNames = (@($webSession.Cookies.GetCookies($baseUrl) | ForEach-Object { $_.Name }) -join ",")
        $probeBody = "// preflight-probe-$(Get-Date -Format 'yyyyMMddHHmmss')%0Aint main(){return 0;}"
        $submit = Invoke-WebRequest -UseBasicParsing -Method Post `
            -Uri "$baseUrl/api/problems/$($Seed.firstProblemId)/submissions" -ContentType "application/json" `
            -Body (ConvertTo-Json @{ code = $probeBody } -Compress) -WebSession $webSession -TimeoutSec 15
        $probe.submissionStatus = [int]$submit.StatusCode
        $probe.submissionBody = (Get-ResponseText -Response $submit)
    } catch {
        $probe.error = $_.Exception.Message
    }
    $report.probe = $probe

    # Nothing the preflight wrote may still be running when the warm-up's baseline is taken, so the
    # preflight's contest is drained exactly as the warm-up's is before it.
    $report.quiescence = Wait-PipelineQuiescent -ContestId ([long]$Seed.contestId) -TimeoutSeconds 300 `
        -Purpose "the preflight contest" -AllowMissingExecutorGauges:($DispatchMode -eq "rabbit")

    $problems = New-Object System.Collections.Generic.List[string]
    if ($portMapping -notmatch "18080") {
        $problems.Add("the published port for nginx:80 is '$portMapping' rather than a mapping onto 18080, so the load would not reach the ingress this experiment measures")
    }
    $down = @($readinessFailedConfirmations)
    if (-not $readinessUp) {
        $last = if ($readiness.Count -gt 0) { $readiness[$readiness.Count - 1].status } else { "no reading was taken" }
        $problems.Add("readiness through the published port never reported UP within ${PreflightReadinessTimeoutSeconds}s ($($readiness.Count) polls, last: $last)")
    } elseif ($down.Count -gt 0) {
        $problems.Add("readiness did not stay UP through the published port: $($down.Count) of $($readinessConfirmations.Count) confirmation probes answered otherwise (last: $($down[-1].status))")
    }
    $badContainers = @($report.containers | Where-Object { $_ -notmatch '\|running\|exit=0\|oom=false\|restarts=0\|' })
    if ($badContainers.Count -gt 0) {
        $problems.Add("$($badContainers.Count) containers are not in the state a comparison can start from (not running, non-zero exit, OOM-killed, or restarted): $($badContainers -join '; ')")
    }
    if ($refusals.Count -gt 0) {
        $problems.Add("$($refusals.Count) requests were refused at the published port during the preflight ($($refusedLogins.Count) logins, $($refusedSubmits.Count) submissions); section 3 stops the comparison here rather than measuring an ingress that cannot carry it")
    }
    if ($stages.Count -eq 0) {
        $problems.Add("the preflight trace file is missing or incomplete, so the instant the submissions were released at cannot be placed")
    }
    if ($null -ne $loginsInHold -and $loginsInHold -gt 0) {
        $problems.Add("$loginsInHold logins were sent after the gate: the submission load was released before every session was established")
    }
    if ($null -ne $sessionsStartedAfterGate -and $sessionsStartedAfterGate -gt 0) {
        $problems.Add("$sessionsStartedAfterGate sessions started after the gate, which is a replaced session rather than a prepared one")
    }
    if ($probe.loginStatus -ne 200 -or $probe.submissionStatus -ne 202) {
        $problems.Add("the login-plus-submit probe did not answer 200 then 202 (login $($probe.loginStatus), submission $($probe.submissionStatus), error $($probe.error))")
    }
    if (-not $report.quiescence.quiescent) {
        $problems.Add("the preflight contest never reached quiescence ($($report.quiescence.reason))")
    }
    if ($null -ne $phase.process.ExitCode -and $phase.process.ExitCode -eq 2) {
        # Recorded rather than fatal: the preflight runs at the measured rate, and Gatling's own
        # latency assertion firing there is a finding about the stack, not an ingress failure.
        $report.gatlingAssertionFailed = $true
    }

    # Kept whether or not the preflight passed: the nginx log beside a refusal count is the evidence
    # that the refusal happened below the application, and on this stack that is the whole question.
    Save-ContainerLog -Service "nginx" -Path (Join-Path $runDirectory "preflight-nginx.log")
    $report.containers = @(Get-ContainerStates)
    $report.problems = @($problems)
    $report.verdict = if ($problems.Count -eq 0) { "pass" } else { "failed" }
    $report.finishedAt = [datetimeoffset]::UtcNow.ToString("o")
    $report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDirectory "preflight.json") -Encoding utf8

    if ($problems.Count -gt 0) {
        throw "Ingress preflight failed at $($report.finishedAt): $(@($problems) -join ' | ')"
    }
    return $report
}

function Start-GatlingProcess {
    param([Parameter(Mandatory = $true)][string[]]$JavaArgs)
    # Start-Process -PassThru -NoNewWindow hands back a Process whose ExitCode stays empty on this
    # PowerShell 5.1 even after WaitForExit(), which made the assertion check read $null and fail
    # every run with "Gatling exited with code .". System.Diagnostics.Process populates it, and
    # UseShellExecute = $false without output redirection still lets Gatling write to this console.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = (Get-Command java.exe).Source
    $startInfo.UseShellExecute = $false
    $startInfo.Arguments = (($JavaArgs | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' ')
    $process = [System.Diagnostics.Process]::Start($startInfo)
    # Recorded so the failure path can stop it. A load generator left pointing at a stack that is
    # being removed reports nothing but client-side connection errors, which read like a server
    # fault: the first normal-timeout run aborted before its warm-up window and the only symptom
    # left behind was 881 "Premature close" errors that were the teardown racing a live JVM.
    $script:lastGatlingProcess = $process
    return $process
}

function Start-GatlingLoadPhase {
    param(
        [Parameter(Mandatory = $true)][string]$PhaseName,
        [Parameter(Mandatory = $true)]$Seed,
        [Parameter(Mandatory = $true)][string]$UserPrefix,
        [Parameter(Mandatory = $true)][double]$Rps,
        [Parameter(Mandatory = $true)][int]$HoldSeconds,
        [string]$TracePath = ""
    )
    # Used by the normal-timeout warm-up phase only. The measured phase keeps its own inline
    # invocation: that path already carries the load-start capacity sample, the fault scheduling and
    # the first-submission anchor that the already-published runs were measured with, and rewriting
    # it to share this helper would put a refactor between those numbers and the code that produced
    # them for no gain here.
    # Every -D property must come before -cp: the JVM reads them up to the class name, and anything
    # after it is a program argument, which is where the trace property used to sit. Gatling then
    # answered with "Unknown option -Dperf.stageTraceFile=..." and wrote no trace at all, so the
    # harness had no warm-up window to place and aborted the run.
    $phaseProperties = @(
        "-Xms256m", "-Xmx1g", "-Dperf.baseUrl=$baseUrl", "-Dperf.assert.minRequests=1",
        "-Dperf.assert.minSuccessPercent=$assertMinSuccess", "-Dperf.assert.p95Millis=$AssertP95Millis",
        "-Dperf.submitIntervalMillis=3100", "-Dperf.userPrefix=$UserPrefix", "-Dperf.workloadSeed=$LatencySeed",
        "-Dperf.userIndex.start=1", "-Dperf.userIndex.end=$UserCount",
        "-Dperf.contestId=$($Seed.contestId)", "-Dperf.problemId.start=$($Seed.firstProblemId)", "-Dperf.problemId.end=$($Seed.lastProblemId)",
        "-Dperf.rampSeconds=$RampSeconds", "-Dperf.stepHoldSeconds=$HoldSeconds",
        "-Dperf.stageRps=$Rps", "-Dperf.warmupStageCount=0",
        # Passed on both phases rather than on the measured one alone, so the two contests are run by
        # the same model: the ramp prepares the population's sessions in each and the first submission
        # is released at its end. Absent this the warm-up would be the older model while the
        # measurement was the new one, and the stack the measurement starts against would not be the
        # stack this phase built.
        "-Dperf.authPrepSeconds=$AuthPrepSeconds"
    )
    if ($TracePath) { $phaseProperties += "-Dperf.stageTraceFile=$TracePath" }
    $phaseArgs = $phaseProperties + @(
        "-cp", $classpath, "io.gatling.app.Gatling", "-s", "my.oj.perf.ContestSubmissionStepLoadSimulation",
        "-rf", $resultsFolder, "-rd", "mysql-judge-tradeoff-$RunId-$PhaseName"
    )
    $startedAt = Get-Date
    $process = Start-GatlingProcess -JavaArgs $phaseArgs
    # Anchor the phase clock to the first persisted submission rather than to process creation:
    # Java and Gatling startup can take longer than the ramp.
    $deadline = (Get-Date).AddSeconds(60)
    $persisted = $null
    do {
        $persisted = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($Seed.contestId)"
        if ($null -ne $persisted -and $persisted -gt 0) { break }
        if ($process.HasExited) { throw "Gatling exited during the $PhaseName phase before the first submission was persisted." }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $persisted -or $persisted -eq 0) { throw "No submission was persisted within 60 seconds of starting the $PhaseName phase." }
    return [pscustomobject]@{
        process = $process
        startedAt = $startedAt
        firstSubmissionAt = [datetimeoffset]::UtcNow.ToString("o")
    }
}

function Get-JudgeLiveState {
    param([Parameter(Mandatory = $true)][int]$Port)
    # One scrape for everything the quiescence gate needs. A counter read from a live endpoint is
    # what makes "no warm-up work is still running" decidable: the measured phase's judge-invocation
    # delta is end minus start, so any invocation counter increment after the baseline is charged to
    # the measured window. An unreachable node returns nulls, never zeros.
    $state = @{ running = $null; queued = $null; reserved = $null; invocations = $null }
    try {
        $content = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$Port/actuator/prometheus").Content
        foreach ($metric in @("running", "queued", "reserved")) {
            $match = [regex]::Match($content, "(?m)^contest_judge_executor_$metric(?:\{[^}]*\})?\s+([^\s]+)$")
            if ($match.Success) { $state[$metric] = [double]$match.Groups[1].Value }
        }
        $invocation = [regex]::Match($content, "(?m)^contest_judge_invocations_total(?:\{[^}]*\})?\s+([^\s]+)$")
        if ($invocation.Success) { $state.invocations = [double]$invocation.Groups[1].Value }
    } catch {
        $state = @{ running = $null; queued = $null; reserved = $null; invocations = $null }
    }
    return $state
}

function Wait-GatlingWithSamples {
    param(
        [Parameter(Mandatory = $true)]$Process,
        $Trace,
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)][string]$Phase
    )
    # The warm-up phase's tick loop. It is the measured phase's loop minus the boundary snapshots,
    # which only exist for the per-stage counter deltas of a staircase, and it exists separately
    # rather than by rewriting the measured loop so that the loop the published capacity runs were
    # sampled with stays exactly as it was measured.
    $script:staircaseLastTickUtc = $null
    $nextTick = [datetimeoffset]::UtcNow
    while (-not $Process.HasExited) {
        $nextTick = $nextTick.AddSeconds(1)
        Save-StaircaseSample -Phase $Phase -Trace $Trace -ContestId $ContestId | Out-Null
        $remaining = ($nextTick - [datetimeoffset]::UtcNow).TotalMilliseconds
        if ($remaining -gt 0) {
            Start-Sleep -Milliseconds ([math]::Round($remaining))
        } elseif ($remaining -lt -1000) {
            $nextTick = [datetimeoffset]::UtcNow
        }
    }
}

function Wait-PipelineQuiescent {
    param(
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [int]$StableSeconds = 3,
        [string]$Purpose = "this contest",
        # The executor gauges are registered by the MySQL dispatcher only, so a rabbit run has no
        # "reserved" to read and would otherwise never be callable quiescent. This is passed from
        # the dispatch mode rather than inferred from a missing scrape, so an unavailable reading on
        # a path that does have the gauge still blocks quiescence as before.
        [switch]$AllowMissingExecutorGauges
    )
    # A single "backlog reads zero" sample is not enough to place a metric baseline. Three gaps make
    # it insufficient. First, a row is PUBLISHED the moment it is handed to a worker, so an
    # unfinished count of zero does not mean the judges are idle: the judge invocation counter is
    # incremented in the worker's own finally block, after the row has already stopped being
    # "unfinished". Second, the judge-result batch writer persists results up to one linger behind
    # its last publish, so a result row can appear after the invocation was counted, and the reverse
    # ordering is possible too. Third, work that starts after the baseline is charged to the
    # measured window's counter delta, so the baseline is only clean if nothing else is still
    # running. Quiescence is therefore this whole predicate held for consecutive seconds: nothing
    # unfinished anywhere in the outbox, no judge holding a claim, this contest's submissions all
    # judged and all applied to the scoreboard, and the judge invocation total unchanged.
    #
    # "no judge holding a claim" is the one term that is dispatch-specific. Under rabbit a message
    # handed to a consumer is already PUBLISHED in the outbox and has no result row until the judge
    # stores one, so results == accepted is what says nothing is in flight there, and it is the same
    # invariant the integrity check reports at the end. The term is dropped only when the caller
    # says the path has no gauge to read, and the reason string records that it was dropped.
    $startedAt = [datetimeoffset]::UtcNow
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $streak = 0
    $lastAccepted = $null
    $lastInvocations = $null
    $reason = "no reading was taken for $Purpose"
    $invocationsAtQuiescence = $null
    while ((Get-Date) -lt $deadline) {
        $unfinished = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
        $accepted = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$ContestId"
        $results = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId"
        $applied = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId AND scoreboard_applied_at IS NOT NULL"
        $reserved = 0.0
        $invocations = 0.0
        $readable = $true
        $gaugesMissing = $false
        foreach ($port in @(19001, 19002)) {
            $state = Get-JudgeLiveState -Port $port
            if ($null -eq $state.invocations) { $readable = $false; continue }
            $invocations += $state.invocations
            if ($null -eq $state.reserved) {
                if ($AllowMissingExecutorGauges) { $gaugesMissing = $true } else { $readable = $false }
            } else {
                $reserved += $state.reserved
            }
        }
        $quiet = $false
        if ($readable -and $null -ne $unfinished -and $null -ne $accepted -and $null -ne $results -and $null -ne $applied) {
            $gaugeTerm = if ($gaugesMissing) { "executor reserved gauge absent on this dispatch path and dropped" } else { "judge reserved $reserved" }
            $reason = "outbox unfinished $unfinished, $gaugeTerm, accepted $accepted, results $results, scoreboard applied $applied, judge invocations $invocations"
            $quiet = ($unfinished -eq 0 -and ($gaugesMissing -or $reserved -eq 0) -and $results -eq $accepted -and $applied -eq $accepted -and
                ($null -eq $lastInvocations -or $invocations -eq $lastInvocations))
        } else {
            $reason = "at least one reading was unavailable or the judge endpoints could not be scraped (outbox $unfinished, accepted $accepted, results $results, applied $applied, both nodes readable $readable)"
        }
        # A count that moves restarts the streak even if every predicate happens to hold, so a
        # submission arriving mid-window cannot be averaged into a "stable" window.
        if ($quiet -and ($null -eq $lastAccepted -or $accepted -eq $lastAccepted)) { $streak++ } else { $streak = 0 }
        $lastAccepted = $accepted
        $lastInvocations = $invocations
        if ($quiet -and $streak -ge $StableSeconds) {
            $invocationsAtQuiescence = $invocations
            return [pscustomobject]@{
                quiescent = $true
                seconds = [math]::Round(([datetimeoffset]::UtcNow - $startedAt).TotalSeconds, 3)
                stableSeconds = $StableSeconds
                accepted = $accepted
                judgeInvocations = $invocationsAtQuiescence
                reason = $reason
            }
        }
        Start-Sleep -Seconds 1
    }
    return [pscustomobject]@{
        quiescent = $false
        seconds = [math]::Round(([datetimeoffset]::UtcNow - $startedAt).TotalSeconds, 3)
        stableSeconds = $StableSeconds
        accepted = $lastAccepted
        judgeInvocations = $invocationsAtQuiescence
        reason = $reason
    }
}

function Get-StaircaseTrace {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    $rows = @(Import-Csv -Path $Path -Header epochMillis, event, segmentIndex, kind, stageIndex, isWarmup, population, targetRps)
    $anchor = @($rows | Where-Object { $_.event -eq "anchor" })
    $done = @($rows | Where-Object { $_.event -eq "planDone" })
    # A partially written plan is not a plan: refuse it rather than guess the missing boundaries.
    if ($anchor.Count -eq 0 -or $done.Count -eq 0) { return $null }
    $segments = New-Object System.Collections.Generic.List[object]
    $open = $null
    foreach ($row in $rows) {
        if ($row.event -eq "segmentStart") {
            $open = [pscustomobject]@{
                index = [int]$row.segmentIndex; kind = $row.kind; stageIndex = [int]$row.stageIndex
                isWarmup = ($row.isWarmup -eq "1"); population = [int]$row.population
                targetRps = [double]$row.targetRps; startMillis = [long]$row.epochMillis; endMillis = $null
            }
        } elseif ($row.event -eq "segmentEnd" -and $null -ne $open -and $open.index -eq [int]$row.segmentIndex) {
            $open.endMillis = [long]$row.epochMillis
            $segments.Add($open)
            $open = $null
        }
    }
    if ($open) { return $null }
    return [pscustomobject]@{
        anchorMillis = [long]$anchor[0].epochMillis
        planEndMillis = [long]$done[0].epochMillis
        segments = $segments
    }
}

function Get-StaircaseStages {
    param($Trace)
    $stages = New-Object System.Collections.Generic.List[object]
    foreach ($segment in @($Trace.segments | Where-Object { $_.kind -eq "hold" })) {
        $stages.Add([pscustomobject]@{
            stageIndex = $segment.stageIndex
            label = if ($segment.isWarmup) { "warmup" } else { "stage-$($segment.stageIndex)" }
            isWarmup = $segment.isWarmup
            targetRps = $segment.targetRps
            population = $segment.population
            startMillis = $segment.startMillis
            endMillis = [long]$segment.endMillis
            # The guard drops the head of the hold, where users added by the preceding ramp are
            # still delivering their first (jittered) submission, which puts the offered rate
            # above the stage target.
            measurementStartMillis = [math]::Min([long]$segment.startMillis + ($SteadyGuardSeconds * 1000), [long]$segment.endMillis)
            measurementEndMillis = [long]$segment.endMillis
            traceSegmentIndex = $segment.index
            prometheusStartLabel = "seg-$($segment.index)-start"
            prometheusMeasurementStartLabel = "seg-$($segment.index)-measurement-start"
            prometheusEndLabel = "seg-$($segment.index)-end"
        })
    }
    return $stages
}

function Resolve-StaircasePosition {
    param($Trace, [long]$NowMillis)
    if ($null -eq $Trace) { return [pscustomobject]@{ label = "pre-load"; stageIndex = $null; targetRps = $null; inHold = $false } }
    $current = $null
    foreach ($segment in $Trace.segments) {
        if ($NowMillis -ge $segment.startMillis -and $NowMillis -lt $segment.endMillis) { $current = $segment; break }
    }
    if ($null -eq $current) {
        $label = if ($NowMillis -ge $Trace.planEndMillis) { "post-run" } else { "pre-load" }
        return [pscustomobject]@{ label = $label; stageIndex = $null; targetRps = $null; inHold = $false }
    }
    if ($current.kind -eq "hold") {
        $label = if ($current.isWarmup) { "warmup" } else { "stage-$($current.stageIndex)" }
        return [pscustomobject]@{ label = $label; stageIndex = $current.stageIndex; targetRps = $current.targetRps; inHold = $true }
    }
    return [pscustomobject]@{ label = "transition-$($current.stageIndex)"; stageIndex = $null; targetRps = $current.targetRps; inHold = $false }
}

function Save-StaircaseSample {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        $Trace,
        [Parameter(Mandatory = $true)][long]$ContestId
    )
    $path = Join-Path $runDirectory "timeseries.csv"
    if (-not (Test-Path $path)) {
        "timestamp,epochMillis,sampleIntervalMs,sampleElapsedMs,phase,stageIndex,stageLabel,targetRps,inHold," +
        "acceptedTotal,resultsTotal,scoreboardTotal,unfinishedOutbox,unfinishedOutboxGlobal,unappliedScoreboard," +
        "judge1Running,judge1Queued,judge1Reserved,judge2Running,judge2Queued,judge2Reserved," +
        "threadsConnected,threadsRunning,innodbRowLockCurrentWaits,innodbRowLockWaits,questions" |
            Set-Content $path -Encoding utf8
    }

    $tickStart = [datetimeoffset]::UtcNow
    $sampleIntervalMs = if ($null -ne $script:staircaseLastTickUtc) {
        [math]::Round(($tickStart - $script:staircaseLastTickUtc).TotalMilliseconds, 1)
    } else { "" }
    $script:staircaseLastTickUtc = $tickStart

    # One statement, one round trip. The stock mysql:8.0 image exposes no CPU counter, so the
    # connection and InnoDB lock counters are what the container can actually report.
    $sql = @"
SELECT 'accepted', COUNT(*) FROM contest_submission WHERE contest_id=$ContestId
UNION ALL SELECT 'results', COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId
UNION ALL SELECT 'scoreboard', COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId AND scoreboard_applied_at IS NOT NULL
UNION ALL SELECT 'unfinishedContest', COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id = o.submission_id WHERE s.contest_id=$ContestId AND o.status <> 'PUBLISHED'
UNION ALL SELECT 'unappliedContest', COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId AND scoreboard_applied_at IS NULL
UNION ALL SELECT 'unfinishedGlobal', COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'
UNION ALL SELECT 'threadsConnected', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'
UNION ALL SELECT 'threadsRunning', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'
UNION ALL SELECT 'rowLockCurrentWaits', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_current_waits'
UNION ALL SELECT 'rowLockWaits', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'
UNION ALL SELECT 'questions', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Questions';
"@
    $values = @{}
    foreach ($row in @(Invoke-SqlRows $sql)) {
        $parts = $row.Split([char]9)
        if ($parts.Count -ge 2) { $values[$parts[0]] = $parts[1] }
    }

    $judge1 = Get-JudgeGauges -Port 19001
    $judge2 = Get-JudgeGauges -Port 19002
    $gatherMs = [math]::Round(([datetimeoffset]::UtcNow - $tickStart).TotalMilliseconds, 1)

    $position = Resolve-StaircasePosition -Trace $Trace -NowMillis $tickStart.ToUnixTimeMilliseconds()
    $stageIndex = if ($null -eq $position.stageIndex) { "" } else { $position.stageIndex }
    $targetRps = if ($null -eq $position.targetRps) { "" } else { $position.targetRps }

    $row = "$($tickStart.ToString('o')),$($tickStart.ToUnixTimeMilliseconds()),$sampleIntervalMs,$gatherMs," +
        "$Phase,$stageIndex,$($position.label),$targetRps,$([int]$position.inHold)," +
        "$($values.accepted),$($values.results),$($values.scoreboard),$($values.unfinishedContest)," +
        "$($values.unfinishedGlobal),$($values.unappliedContest)," +
        "$($judge1.running),$($judge1.queued),$($judge1.reserved)," +
        "$($judge2.running),$($judge2.queued),$($judge2.reserved)," +
        "$($values.threadsConnected),$($values.threadsRunning),$($values.rowLockCurrentWaits)," +
        "$($values.rowLockWaits),$($values.questions)"
    Add-SampleRow -Path $path -Row $row

    # capacity.csv keeps the run-level executor aggregate the analyzer already reports.
    $capacityPath = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $capacityPath)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $capacityPath -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", $judge1), @("judge-2", $judge2))) {
        Add-SampleRow -Path $capacityPath -Row "$($tickStart.ToString('o')),$Phase,$($entry[0]),$($entry[1].running),$($entry[1].queued),$($entry[1].reserved)"
    }

    return [pscustomobject]@{
        unfinishedContest = ConvertTo-Int64OrNull $values.unfinishedContest
        unfinishedGlobal = ConvertTo-Int64OrNull $values.unfinishedGlobal
        unappliedContest = ConvertTo-Int64OrNull $values.unappliedContest
        # Carried for the fault-recovery loop, which derives its rolling result throughput from the
        # same tick it places its backlog samples on. The staircase's own consumers read the three
        # fields above and are unaffected by the two extra ones.
        accepted = ConvertTo-Int64OrNull $values.accepted
        results = ConvertTo-Int64OrNull $values.results
    }
}

function Save-StaircaseBoundarySnapshots {
    param($Trace, [long]$NowMillis, $Captured)
    if ($null -eq $Trace) { return }
    foreach ($segment in $Trace.segments) {
        foreach ($edge in @(@("start", $segment.startMillis), @("end", $segment.endMillis))) {
            $label = "seg-$($segment.index)-$($edge[0])"
            if ($Captured.ContainsKey($label)) { continue }
            if ($NowMillis -lt [long]$edge[1]) { continue }
            # A counter delta is only as good as the pair of scrapes it is taken from, so the
            # scrape is taken live at the boundary and the tick that took it is recorded, which
            # makes its own lag explicit rather than assumed.
            Save-MetricsSnapshot $label
            $Captured[$label] = $NowMillis
        }
        if ($segment.kind -eq "hold") {
            $measurementStart = [math]::Min(
                    [long]$segment.startMillis + ($SteadyGuardSeconds * 1000),
                    [long]$segment.endMillis)
            $label = "seg-$($segment.index)-measurement-start"
            if (-not $Captured.ContainsKey($label) -and $NowMillis -ge $measurementStart) {
                Save-MetricsSnapshot $label
                $Captured[$label] = $NowMillis
            }
        }
    }
}

function Get-PromMetricSum {
    param([string]$Label, [string]$Metric, [string]$RequiredTag = "", [string]$OnlyNode = "")
    $sum = 0.0; $found = $false
    foreach ($node in @("judge-1", "judge-2")) {
        if ($OnlyNode -and $node -ne $OnlyNode) { continue }
        $path = Join-Path $runDirectory "metrics\$Label-$node.prom"
        if (-not (Test-Path $path)) { continue }
        $snapshot = @(Get-Content $path)
        foreach ($line in $snapshot) {
            if ($line -match ("^" + [regex]::Escape($Metric) + '(?:\{([^}]*)\})?\s+([^\s]+)$')) {
                # Save captures before another -match/-notmatch overwrites PowerShell's
                # automatic $Matches variable.
                $tags = $Matches[1]
                $rawValue = $Matches[2]
                if ($RequiredTag -and $tags -notmatch [regex]::Escape($RequiredTag)) { continue }
                $value = 0.0
                if ([double]::TryParse($rawValue, [Globalization.NumberStyles]::Float,
                        [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
                    $sum += $value; $found = $true
                }
            }
        }
    }
    if (-not $found) { return $null }
    return $sum
}

function Get-PromMetricDelta {
    param([string]$Metric, [string]$RequiredTag = "")
    $total = 0.0
    foreach ($node in @("judge-1", "judge-2")) {
        $start = Get-PromMetricSum "start" $Metric $RequiredTag $node
        $end = Get-PromMetricSum "end" $Metric $RequiredTag $node
        if ($null -eq $end) { return $null }
        # Lazy Micrometer meters do not exist in the startup scrape until first use.
        if ($null -eq $start) { $start = 0.0 }
        # The recombination is only valid when a fault was actually injected: it needs the "pre-fault"
        # scrape, and it is only correct for a node whose JVM was replaced. Keying on the requested
        # mode rather than the injection would silently drop the killed node's whole pre-kill
        # contribution in a recovery run that never fired, by subtracting a baseline that was never
        # taken. So the flag is set at the injection, not from the parameters.
        if ($faultWasInjected -and $node -eq $KilledNode) {
            $beforeKill = Get-PromMetricSum "pre-fault" $Metric $RequiredTag $node
            if ($null -eq $beforeKill) { $beforeKill = 0.0 }
            # The killed JVM contributes start..pre-fault. Its replacement JVM
            # starts counters at zero, so its complete end value is the recovery
            # contribution; subtracting a post-restart scrape would drop work.
            # The sum is invariant to when "pre-fault" was taken, so taking it as
            # late as possible only shrinks the window whose increments are lost.
            $total += [math]::Max(0, $beforeKill - $start) + [math]::Max(0, $end)
        } else {
            $total += [math]::Max(0, $end - $start)
        }
    }
    return $total
}

function Get-PercentileValue {
    param([object[]]$Values, [double]$Percentile)
    # Nearest rank, the same rule as the analyzer's Get-Percentile, so a baseline computed here and
    # the same baseline recomputed from timeseries.csv cannot disagree about the same sample set.
    $clean = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($clean.Count -eq 0) { return $null }
    $index = [int][math]::Max(0, [math]::Ceiling($clean.Count * $Percentile) - 1)
    if ($index -ge $clean.Count) { $index = $clean.Count - 1 }
    return [double]$clean[$index]
}

function Wait-UntilDeadline {
    param(
        [Parameter(Mandatory = $true)][datetimeoffset]$Deadline,
        [int]$QuantumMilliseconds = 200
    )
    # The only clock authority for a deadline this experiment has to hit. It performs no I/O, takes no
    # sample and runs no analysis, so nothing observed elsewhere in the loop can move the deadline it
    # waits for. The overshoot is bounded by the final slice and is reported by the caller as an
    # explicit error rather than absorbed silently.
    while ($true) {
        $remaining = ($Deadline - [datetimeoffset]::UtcNow).TotalMilliseconds
        if ($remaining -le 0) { return }
        $slice = [int][math]::Min($QuantumMilliseconds, [math]::Ceiling($remaining))
        if ($slice -lt 1) { $slice = 1 }
        Start-Sleep -Milliseconds $slice
    }
}

function Get-DockerField {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    # A single-value docker read whose stdout is parsed. Same stderr-to-file discipline as
    # Invoke-Compose: under $ErrorActionPreference = "Stop" a native command's stderr becomes a
    # terminating ErrorRecord, which would turn a benign inspect warning into a failed run.
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & docker @Arguments 2>$stderrFile
        } finally { $ErrorActionPreference = $previousPreference }
        if ($LASTEXITCODE -ne 0) { return $null }
        return ((@($output | ForEach-Object { [string]$_ }) -join "`n").Trim())
    } finally {
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue
    }
}

function Wait-ContainerRunning {
    param(
        [Parameter(Mandatory = $true)][string]$Node,
        [int]$TimeoutSeconds = 180,
        [scriptblock]$OnPoll = $null
    )
    # Deliberately not Wait-Healthy. That gate requires all nine containers at once and calls
    # docker inspect on every one of them, so it cannot pass while a node is down and its two second
    # poll would hold the restart window open. This asks about one container and returns the instant
    # it first reads running.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $containerId = ""
    while ((Get-Date) -lt $deadline) {
        if (-not $containerId) {
            $candidates = @(Invoke-Compose -Arguments @("ps", "-q", $Node) | Where-Object { $_ })
            if ($candidates.Count -gt 0) { $containerId = $candidates[0] }
        }
        if ($containerId) {
            $running = Get-DockerField -Arguments @("inspect", "--format", "{{.State.Running}}", $containerId)
            if ("$running" -eq "true") { return [datetimeoffset]::UtcNow }
        }
        if ($null -ne $OnPoll) { & $OnPoll }
        Start-Sleep -Milliseconds 200
    }
    throw "$Node did not report a running container within $TimeoutSeconds seconds."
}

function Test-NodeReadiness {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$Port/actuator/health/readiness"
        $httpStatus = [int]$response.StatusCode
        $text = Get-ResponseText -Response $response
        # Every path returns a status string that says something. An empty status in the timeout
        # message is what made the first smoke run's readiness failure unreadable, so a body that
        # cannot be interpreted is reported as its own observation rather than as silence.
        if ([string]::IsNullOrWhiteSpace($text)) {
            return [pscustomobject]@{ up = $false; httpStatus = $httpStatus; status = "empty body (HTTP $httpStatus)" }
        }
        $document = $text | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $document.status) {
            $excerpt = $text.Substring(0, [math]::Min(120, $text.Length))
            return [pscustomobject]@{
                up = $false; httpStatus = $httpStatus
                status = "no status field (HTTP $httpStatus): $excerpt"
            }
        }
        return [pscustomobject]@{ up = ("$($document.status)" -eq "UP"); httpStatus = $httpStatus; status = [string]$document.status }
    } catch {
        # Spring answers 503 while the readiness group is DOWN, and 503 is an exception here, so the
        # transition to UP is the observable event rather than a status string that was read.
        #
        # The response body is read back when there is one, because a 5xx is the only answer that
        # cannot be interpreted from its status: Spring's health endpoint returns a document naming
        # the indicators that failed, and a 500 there carries the exception that threw. Without it a
        # readiness failure reads as "it said 500", which is an observation and not a diagnosis.
        $excerpt = ""
        $response = $null
        if ($null -ne $_.Exception.Response) {
            $response = $_.Exception.Response
        } elseif ($null -ne $_.Exception.InnerException -and $null -ne $_.Exception.InnerException.Response) {
            $response = $_.Exception.InnerException.Response
        }
        if ($null -ne $response) {
            try {
                $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
                $body = $reader.ReadToEnd()
                $reader.Close()
                if (-not [string]::IsNullOrWhiteSpace($body)) {
                    $excerpt = " body: " + ($body -replace "\s+", " ").Substring(0, [math]::Min(200, ($body -replace "\s+", " ").Length))
                }
            } catch { }
        }
        return [pscustomobject]@{ up = $false; httpStatus = $null; status = "unreachable-or-down: $($_.Exception.Message)$excerpt" }
    }
}

function Get-ClaimCallsTotal {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        $content = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$Port/actuator/prometheus").Content
        $match = [regex]::Match($content, "(?m)^contest_judge_claim_calls_total(?:\{[^}]*\})?\s+([^\s]+)$")
        if (-not $match.Success) { return $null }
        $value = 0.0
        if (-not [double]::TryParse($match.Groups[1].Value, [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { return $null }
        return $value
    } catch { return $null }
}

function Wait-JudgeNodeReady {
    param(
        [Parameter(Mandatory = $true)][string]$Node,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$ReadinessTimeoutSeconds = 180,
        [int]$DispatcherTimeoutSeconds = 90,
        [scriptblock]$OnPoll = $null,
        $Progress = $null
    )
    # Four gates in order, because each one's evidence only means something once the previous holds.
    # gate 1: the container is running. gate 2: Spring's readiness group is UP. gate 3: the judge's
    # metrics endpoint can be scraped. gate 4: the dispatcher has actually claimed since it started.
    # Gate 4 is the only one that distinguishes "a JVM is up" from "the judge is serving": the judge
    # container's own healthcheck is `grep -aq java /proc/1/cmdline`, so docker calls it healthy as
    # soon as PID 1 is Java. The replacement JVM's counters start at zero, which makes the first
    # scrape a floor rather than a baseline - a strictly larger later value is what proves polling.
    #
    # $Progress is a sink the caller keeps. Each gate's instant is written into it the moment that
    # gate passes, so a later gate's timeout leaves the earlier evidence intact: the first smoke run
    # lost containerRunningAt and readinessAt entirely because they were only assigned on the success
    # path, which made "readiness never came up" indistinguishable from "the container never started".
    # $OnPoll is the caller's observation tick. A fault run needs its 1s series to keep running across
    # the restart; every gate below checks its own deadline before polling, so a slow tick can delay
    # the next check but cannot move a deadline.
    $containerRunningAt = Wait-ContainerRunning -Node $Node -TimeoutSeconds $ReadinessTimeoutSeconds -OnPoll $OnPoll
    if ($null -ne $Progress) { $Progress.containerRunningAt = $containerRunningAt }
    $readinessDeadline = (Get-Date).AddSeconds($ReadinessTimeoutSeconds)
    $readinessAt = $null
    $lastReadiness = ""
    $lastHttpStatus = $null
    $readinessAttempts = 0
    while ((Get-Date) -lt $readinessDeadline) {
        $probe = Test-NodeReadiness -Port $Port
        $readinessAttempts++
        $lastReadiness = $probe.status
        $lastHttpStatus = $probe.httpStatus
        if ($probe.up) { $readinessAt = [datetimeoffset]::UtcNow; break }
        if ($null -ne $OnPoll) { & $OnPoll }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $readinessAt) {
        throw ("$Node readiness probe never reported UP within $ReadinessTimeoutSeconds seconds " +
            "($readinessAttempts attempts, last status: $lastReadiness | last HTTP: " +
            "$(if ($null -eq $lastHttpStatus) { 'none' } else { $lastHttpStatus })). " +
            "The container has been running since $($containerRunningAt.ToString('o')), so this is a " +
            "readiness-group or endpoint failure rather than a restart failure.")
    }
    if ($null -ne $Progress) { $Progress.readinessAt = $readinessAt }
    Wait-JudgeMetrics -Node $Node -OnPoll $OnPoll
    $metricsAt = [datetimeoffset]::UtcNow
    if ($null -ne $Progress) { $Progress.metricsAt = $metricsAt }
    $claimCallsAtFirstScrape = Get-ClaimCallsTotal -Port $Port
    $dispatcherDeadline = (Get-Date).AddSeconds($DispatcherTimeoutSeconds)
    $dispatcherActiveAt = $null
    $claimCallsLast = $claimCallsAtFirstScrape
    while ((Get-Date) -lt $dispatcherDeadline) {
        if ($null -ne $OnPoll) { & $OnPoll }
        Start-Sleep -Milliseconds 500
        $claimCallsLast = Get-ClaimCallsTotal -Port $Port
        if ($null -ne $claimCallsLast -and ($null -eq $claimCallsAtFirstScrape -or $claimCallsLast -gt $claimCallsAtFirstScrape)) {
            $dispatcherActiveAt = [datetimeoffset]::UtcNow
            break
        }
    }
    if ($null -eq $dispatcherActiveAt) {
        throw ("$Node never showed contest_judge_claim_calls_total advancing within " +
            "$DispatcherTimeoutSeconds seconds (first scrape $(if ($null -eq $claimCallsAtFirstScrape) { 'unavailable' } else { $claimCallsAtFirstScrape }), " +
            "last $(if ($null -eq $claimCallsLast) { 'unavailable' } else { $claimCallsLast })), so its " +
            "dispatcher is not confirmed active. Readiness was reached at $($readinessAt.ToString('o')).")
    }
    if ($null -ne $Progress) { $Progress.dispatcherActiveAt = $dispatcherActiveAt }
    return [pscustomobject]@{
        containerRunningAt = $containerRunningAt
        readinessAt = $readinessAt
        metricsAt = $metricsAt
        dispatcherActiveAt = $dispatcherActiveAt
        nodeReadyAt = $dispatcherActiveAt
        containerToReadinessSeconds = [math]::Round(($readinessAt - $containerRunningAt).TotalSeconds, 3)
        readinessToMetricsSeconds = [math]::Round(($metricsAt - $readinessAt).TotalSeconds, 3)
        metricsToDispatcherSeconds = [math]::Round(($dispatcherActiveAt - $metricsAt).TotalSeconds, 3)
        readinessProbeAttempts = $readinessAttempts
        lastReadinessStatus = $lastReadiness
        claimCallsAtFirstScrape = $claimCallsAtFirstScrape
        claimCallsWhenActive = $claimCallsLast
    }
}

function ConvertFrom-DbTimestamp {
    param([string]$Value)
    # Both sides of a claimed_at age come from MySQL in the same text form, so they are parsed with
    # the same rule and the difference is not polluted by a client-side clock.
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $parsed = [datetime]::MinValue
    $styles = [Globalization.DateTimeStyles]::AllowWhiteSpaces
    if (-not [datetime]::TryParse($Value.Trim(), [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$parsed)) { return $null }
    return $parsed
}

function Save-FaultSnapshot {
    param($Trigger, $ClaimSnapshot)
    # Everything here is captured as close to one instant as the round trips allow, and each part's own
    # cost is recorded so the offset from faultInjectedAt is auditable rather than assumed. The claimed
    # rows come from Save-ClaimSnapshot, which is a cluster-wide upper bound: the schema has no
    # claimed_by column, so rows held by the node that is about to die cannot be separated from the
    # other node's. That is why nothing here is named after the killed node's claims.
    $startedAt = [datetimeoffset]::UtcNow
    $dbNowRows = @(Invoke-SqlRows "SELECT DATE_FORMAT(CURRENT_TIMESTAMP(6), '%Y-%m-%d %H:%i:%s.%f')")
    $dbNowText = if ($dbNowRows.Count -gt 0) { [string]$dbNowRows[0] } else { "" }
    $unfinishedGlobal = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
    $unfinishedContest = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id = o.submission_id WHERE s.contest_id=$($events.contestId) AND o.status <> 'PUBLISHED'"
    $scoreboardPending = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($events.contestId) AND scoreboard_applied_at IS NULL"

    $dbNow = ConvertFrom-DbTimestamp $dbNowText
    $ages = @()
    if ($null -ne $dbNow -and $null -ne $ClaimSnapshot -and $ClaimSnapshot.rows) {
        foreach ($claimRow in $ClaimSnapshot.rows) {
            $claimedAt = ConvertFrom-DbTimestamp $claimRow.claimedAt
            if ($null -ne $claimedAt) { $ages += [math]::Round(($dbNow - $claimedAt).TotalSeconds, 3) }
        }
    }
    $document = [ordered]@{
        capturedAt = [datetimeoffset]::UtcNow.ToString("o")
        dbNow = $dbNowText
        signal = "SIGKILL"
        command = "docker compose -p oj-loadtest ... kill $KilledNode"
        trigger = $Trigger
        atKill = [ordered]@{
            # The gauges are the values the trigger itself read, taken immediately before the kill and
            # time stamped: after the kill the endpoint is gone, so these are the only kill-time
            # executor readings that can exist.
            judge1 = $Trigger.judge1
            judge2 = $Trigger.judge2
            # The trigger's own gauge instant, under the name the trigger record actually uses. This
            # read a field that does not exist on that record, so the timestamp the comment above
            # promises was silently null in every run.
            judge1GaugeObservedAt = $Trigger.observedAt
            unfinishedOutboxGlobal = $unfinishedGlobal
            unfinishedOutboxContest = $unfinishedContest
            scoreboardPending = $scoreboardPending
            unfinishedOutboxBasis = "one COUNT(*) per reading; a missing reading is null, never 0"
            readSeconds = [math]::Round(([datetimeoffset]::UtcNow - $startedAt).TotalSeconds, 3)
        }
        claims = [ordered]@{
            clusterWideClaimedUnfinishedUpperBound = if ($null -eq $ClaimSnapshot) { $null } else { $ClaimSnapshot.observedActiveClaimCount }
            exact = if ($null -eq $ClaimSnapshot) { $null } else { [bool]$ClaimSnapshot.exact }
            basis = "status='PUBLISHING' across every judge node at kill time; the outbox has no claimed_by column, so this is a cluster-wide upper bound and NOT the killed node's active claims"
            ageSeconds = [ordered]@{
                count = $ages.Count
                p50 = Get-PercentileValue -Values $ages -Percentile 0.50
                p95 = Get-PercentileValue -Values $ages -Percentile 0.95
                max = if ($ages.Count -gt 0) { ($ages | Measure-Object -Maximum).Maximum } else { $null }
                basis = "dbNow - claimed_at for the rows above; a claim's lease starts at its own claimed_at, so the earliest possible reclaim after the kill is the configured timeout minus this age"
            }
            rows = "killed-node-claims.csv"
        }
    }
    $document | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDirectory "kill-snapshot.json") -Encoding utf8
    return [pscustomobject]@{ document = $document; ages = $ages; seconds = $document.atKill.readSeconds }
}

function Get-SustainStreak {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][string]$Field,
        $Ceiling,
        [int]$SustainSeconds = 5,
        [double]$MaxSampleGapSeconds = 2.5
    )
    # "Held for N consecutive seconds" is a statement about the wall clock, so the streak is grown
    # until it covers N seconds with every sample inside it at or below the ceiling. Counting N
    # samples and requiring their span to reach N seconds is not the same rule: five samples 1s apart
    # span 4s, so that version excluded precisely the well-behaved series it was written for and could
    # only ever fire on a series with a hole in it - the opposite of its intent. Growing the streak
    # keeps the intent (a hold nobody observed does not count) while making the design case the case
    # that passes.
    #
    # The gap bound is what keeps "consecutive" honest. The sampler ticks at 1s, so one missed tick is
    # tolerated and a stalled sampler is not: a gap wider than 2.5s breaks the streak rather than
    # bridging it, and the widest gap actually covered is returned so the resolution of the answer
    # travels with the answer.
    for ($i = 0; $i -lt $Samples.Count; $i++) {
        $maxGap = 0.0
        for ($j = $i; $j -lt $Samples.Count; $j++) {
            $value = $Samples[$j].$Field
            # A sample whose count did not arrive breaks the streak rather than counting as zero.
            if ($null -eq $value -or $value -gt $Ceiling) { break }
            if ($j -gt $i) {
                $gap = ($Samples[$j].at - $Samples[$j - 1].at).TotalSeconds
                if ($gap -gt $MaxSampleGapSeconds) { break }
                if ($gap -gt $maxGap) { $maxGap = $gap }
            }
            $span = ($Samples[$j].at - $Samples[$i].at).TotalSeconds
            if ($span -ge $SustainSeconds) {
                return [pscustomobject]@{
                    at = $Samples[$i].at
                    spanSeconds = [math]::Round($span, 3)
                    maxGapSeconds = [math]::Round($maxGap, 3)
                    sampleCount = $j - $i + 1
                }
            }
        }
    }
    return $null
}

function Get-BacklogNormalization {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][datetimeoffset]$From,
        $JudgeP95,
        $ScoreboardP95,
        [int]$SustainSeconds = 5,
        [double]$MaxSampleGapSeconds = 2.5
    )
    $result = [pscustomobject]@{
        judgeNormalizedAt = $null; scoreboardNormalizedAt = $null
        combinedNormalizedAt = $null; sustainSpanSeconds = $null
        maxSampleGapSeconds = $null; sustainSampleCount = $null
    }
    $afterFrom = @($Samples | Where-Object { $_.at -ge $From })
    if ($null -eq $JudgeP95 -or $null -eq $ScoreboardP95 -or $afterFrom.Count -lt 2) { return $result }
    $judge = Get-SustainStreak -Samples $afterFrom -Field "unfinished" -Ceiling $JudgeP95 `
        -SustainSeconds $SustainSeconds -MaxSampleGapSeconds $MaxSampleGapSeconds
    $scoreboard = Get-SustainStreak -Samples $afterFrom -Field "unapplied" -Ceiling $ScoreboardP95 `
        -SustainSeconds $SustainSeconds -MaxSampleGapSeconds $MaxSampleGapSeconds
    if ($null -ne $judge) {
        $result.judgeNormalizedAt = $judge.at
        $result.sustainSpanSeconds = $judge.spanSeconds
        $result.maxSampleGapSeconds = $judge.maxGapSeconds
        $result.sustainSampleCount = $judge.sampleCount
    }
    if ($null -ne $scoreboard) { $result.scoreboardNormalizedAt = $scoreboard.at }
    if ($null -ne $result.judgeNormalizedAt -and $null -ne $result.scoreboardNormalizedAt) {
        $result.combinedNormalizedAt = if ($result.judgeNormalizedAt -gt $result.scoreboardNormalizedAt) {
            $result.judgeNormalizedAt
        } else { $result.scoreboardNormalizedAt }
    }
    return $result
}

function Get-FaultRecoveryDerived {
    param(
        [Parameter(Mandatory = $true)][object[]]$Samples,
        [Parameter(Mandatory = $true)][datetimeoffset]$FaultAt,
        $NodeReadyAt,
        [int]$PreFaultSeconds = 30,
        [int]$PreFaultTailExclusionSeconds = 5,
        [int]$SustainSeconds = 5,
        [double]$MaxSampleGapSeconds = 2.5,
        [int]$PostRecoveryDelaySeconds = 10,
        [int]$MinPostRecoverySeconds = 20,
        [double]$ThroughputRecoveryRatio = 0.90,
        [int]$ThroughputWindowSeconds = 5,
        [int]$MaxWindowSpanSeconds = 10,
        [int]$ThroughputConsecutiveWindows = 3
    )
    # Every number here is derived from this run's own 1s samples rather than from a clock, so a
    # sampler that fell behind changes the resolution of the answer and never the answer itself.
    # The judge backlog and the scoreboard backlog are baselined, tested and reported separately:
    # their sum can sit at its usual value while one of the two carries all of the recovery, and a
    # single combined baseline would hide exactly that.
    $baselineFrom = $FaultAt.AddSeconds(-$PreFaultSeconds)
    $baselineTo = $FaultAt.AddSeconds(-$PreFaultTailExclusionSeconds)
    $baseline = @($Samples | Where-Object { $_.at -ge $baselineFrom -and $_.at -lt $baselineTo })
    $judgeBaseline = @($baseline | Where-Object { $null -ne $_.unfinished })
    $scoreboardBaseline = @($baseline | Where-Object { $null -ne $_.unapplied })
    $judgeP95 = Get-PercentileValue -Values @($judgeBaseline | ForEach-Object { $_.unfinished }) -Percentile 0.95
    $scoreboardP95 = Get-PercentileValue -Values @($scoreboardBaseline | ForEach-Object { $_.unapplied }) -Percentile 0.95

    # A single sample below the baseline is not a normalisation: the surviving node drains the backlog
    # while the killed one is still being restarted, so one dip proves nothing about a recovered
    # pipeline. Both conditions must hold together, for the whole sustain window, and a sample whose
    # count did not arrive breaks the streak rather than counting as zero.
    #
    # The reported instant is searched from max(faultInjectedAt, nodeReadyAt) because cohort D is
    # defined as nodeReadyAt -> backlogNormalizedAt, and an instant before the replacement node was
    # serving cannot be the end of that cohort. The same search is also run from faultInjectedAt and
    # reported beside it when it lands earlier, because a surviving node draining the backlog alone is
    # a finding about the lease rather than a rounding error.
    $searchFromAt = $FaultAt
    if ($null -ne $NodeReadyAt -and $NodeReadyAt -gt $FaultAt) { $searchFromAt = $NodeReadyAt }
    $gated = Get-BacklogNormalization -Samples $Samples -From $searchFromAt -JudgeP95 $judgeP95 `
        -ScoreboardP95 $scoreboardP95 -SustainSeconds $SustainSeconds -MaxSampleGapSeconds $MaxSampleGapSeconds
    $ungated = Get-BacklogNormalization -Samples $Samples -From $FaultAt -JudgeP95 $judgeP95 `
        -ScoreboardP95 $scoreboardP95 -SustainSeconds $SustainSeconds -MaxSampleGapSeconds $MaxSampleGapSeconds
    $judgeNormalizedAt = $gated.judgeNormalizedAt
    $scoreboardNormalizedAt = $gated.scoreboardNormalizedAt
    $normalizedAt = $gated.combinedNormalizedAt
    $earliestNormalizedAt = $null
    if ($null -ne $ungated.combinedNormalizedAt -and
        ($null -eq $normalizedAt -or $ungated.combinedNormalizedAt -lt $normalizedAt)) {
        $earliestNormalizedAt = $ungated.combinedNormalizedAt
    }

    # Pre-fault result throughput is measured over the same window the backlog baseline uses, so the
    # two describe one steady state rather than two.
    $preFaultRps = $null
    if ($judgeBaseline.Count -ge 2 -and $null -ne $judgeBaseline[0].results -and $null -ne $judgeBaseline[-1].results) {
        $span = ($judgeBaseline[-1].at - $judgeBaseline[0].at).TotalSeconds
        if ($span -gt 0) { $preFaultRps = ($judgeBaseline[-1].results - $judgeBaseline[0].results) / $span }
    }
    $recoveredAt = $null
    $rolling = @()
    $rollingSpanMaxSeconds = $null
    if ($null -ne $preFaultRps -and $preFaultRps -gt 0) {
        $threshold = $ThroughputRecoveryRatio * $preFaultRps
        for ($i = 1; $i -lt $Samples.Count; $i++) {
            if ($null -eq $Samples[$i].results) { continue }
            $j = $i - 1
            while ($j -gt 0 -and ($Samples[$i].at - $Samples[$j].at).TotalSeconds -lt $ThroughputWindowSeconds) { $j-- }
            $span = ($Samples[$i].at - $Samples[$j].at).TotalSeconds
            # A window spanning much more than its nominal length is not a five second rate: walking
            # back only to "at least 5s earlier" turns a sampling hole into one long average, and the
            # recovery instant read off it would be an artefact of the hole rather than of recovery.
            if ($span -lt $ThroughputWindowSeconds -or $span -gt $MaxWindowSpanSeconds) { continue }
            if ($null -eq $Samples[$j].results) { continue }
            $rolling += [pscustomobject]@{
                at = $Samples[$i].at
                rps = ($Samples[$i].results - $Samples[$j].results) / $span
                span = $span
            }
        }
        if ($rolling.Count -gt 0) {
            $rollingSpanMaxSeconds = [math]::Round((($rolling | Measure-Object -Property span -Maximum).Maximum), 3)
        }
        for ($i = 0; $i -le ($rolling.Count - $ThroughputConsecutiveWindows); $i++) {
            # Neither an instant before the fault nor one before the replacement node was serving can
            # be the throughput recovery. The first is the steady state itself - which is how a smoke
            # run reported a recovery 24s before its own kill - and the second is the outage.
            if ($rolling[$i].at -lt $searchFromAt) { continue }
            $held = $true
            for ($j = $i; $j -lt ($i + $ThroughputConsecutiveWindows); $j++) {
                if ($rolling[$j].rps -lt $threshold) { $held = $false }
            }
            if ($held) { $recoveredAt = $rolling[$i].at; break }
        }
    }

    # The post-recovery steady window is only meaningful if the load was still arriving when it began.
    # A normalisation that lands after the last arrival would otherwise be reported as recovered steady
    # state when it is really an empty pipeline.
    $postRecovery = [ordered]@{
        available = $false
        # The delay before the window opens and the minimum length the window must have are two
        # different numbers. The analyzer's own block used "requestedSeconds" for the second while this
        # one used it for the first, so the two documents disagreed on paper while agreeing on the
        # instant they computed. Both are named explicitly here.
        preWindowDelaySeconds = $PostRecoveryDelaySeconds
        minimumWindowSeconds = $MinPostRecoverySeconds
        actualSeconds = $null
        sampleSpanSeconds = $null
        sampleCount = 0
        reason = "no normalisation was observed"
    }
    if ($null -ne $normalizedAt -and $Samples.Count -gt 0) {
        $from = $normalizedAt.AddSeconds($PostRecoveryDelaySeconds)
        $remaining = $Samples[-1].at - $from
        $window = @($Samples | Where-Object { $_.at -ge $from })
        $postRecovery.actualSeconds = [math]::Round($remaining.TotalSeconds, 3)
        $postRecovery.sampleCount = $window.Count
        if ($window.Count -gt 0) {
            $postRecovery.sampleSpanSeconds = [math]::Round(($window[-1].at - $window[0].at).TotalSeconds, 3)
        }
        if ($remaining.TotalSeconds -lt $MinPostRecoverySeconds) {
            $postRecovery.reason = "only $([math]::Round($remaining.TotalSeconds, 3))s of measured load remained after backlogNormalizedAt + ${PostRecoveryDelaySeconds}s, below the ${MinPostRecoverySeconds}s floor; a shorter window is not a steady state"
        } else {
            $postRecovery.available = $true
            $postRecovery.reason = "held from backlogNormalizedAt + ${PostRecoveryDelaySeconds}s to the end of the measured load"
        }
    }
    return [pscustomobject]@{
        preFaultWindow = [ordered]@{
            from = $baselineFrom.ToString("o"); to = $baselineTo.ToString("o")
            sampleCount = $baseline.Count
            basis = "faultInjectedAt - ${PreFaultSeconds}s to faultInjectedAt - ${PreFaultTailExclusionSeconds}s; the excluded tail keeps arrivals that landed while the trigger was being polled out of the baseline"
        }
        baselines = [ordered]@{
            judgeBacklogP95 = $judgeP95; judgeBacklogSamples = $judgeBaseline.Count
            scoreboardPendingP95 = $scoreboardP95; scoreboardPendingSamples = $scoreboardBaseline.Count
        }
        normalization = [ordered]@{
            judgeBacklogNormalizedAt = if ($null -eq $judgeNormalizedAt) { $null } else { $judgeNormalizedAt.ToString("o") }
            scoreboardBacklogNormalizedAt = if ($null -eq $scoreboardNormalizedAt) { $null } else { $scoreboardNormalizedAt.ToString("o") }
            backlogNormalizedAt = if ($null -eq $normalizedAt) { $null } else { $normalizedAt.ToString("o") }
            # The same search run from faultInjectedAt instead of from max(fault, nodeReadyAt). Reported
            # only when it lands earlier, which is the surviving node draining the backlog alone.
            earliestNormalizedAt = if ($null -eq $earliestNormalizedAt) { $null } else { $earliestNormalizedAt.ToString("o") }
            earliestNormalizedBasis = "searched from faultInjectedAt, so it can precede nodeReadyAt: the surviving node alone can drain the backlog while the killed node is still down"
            searchFromAt = $searchFromAt.ToString("o")
            sustainSeconds = $SustainSeconds
            sustainSpanSeconds = $gated.sustainSpanSeconds
            # How much of the hold rests on samples that were actually taken. maxSampleGapSeconds is the
            # widest gap inside the accepted streak, so a hold confirmed by a 1s series and one confirmed
            # by three samples across a 2.5s gap are told apart here instead of looking identical.
            maxSampleGapSeconds = $gated.maxSampleGapSeconds
            sustainSampleCount = $gated.sustainSampleCount
            maxSampleGapLimitSeconds = $MaxSampleGapSeconds
            basis = "the first sample after which each backlog stayed at or below its own pre-fault p95 for ${SustainSeconds}s of wall clock, searched from max(faultInjectedAt, nodeReadyAt); the streak is grown until it covers ${SustainSeconds}s with every sample at or below the baseline and no gap inside it wider than ${MaxSampleGapSeconds}s, so a well-observed 1s series satisfies it and a sampling hole does not. The combined instant is the later of the two, and the two are reported separately"
            judgeSecondsAfterFault = if ($null -eq $judgeNormalizedAt) { $null } else { [math]::Round(($judgeNormalizedAt - $FaultAt).TotalSeconds, 3) }
            scoreboardSecondsAfterFault = if ($null -eq $scoreboardNormalizedAt) { $null } else { [math]::Round(($scoreboardNormalizedAt - $FaultAt).TotalSeconds, 3) }
            combinedSecondsAfterFault = if ($null -eq $normalizedAt) { $null } else { [math]::Round(($normalizedAt - $FaultAt).TotalSeconds, 3) }
            combinedSecondsAfterNodeReady = if ($null -eq $normalizedAt -or $null -eq $NodeReadyAt) { $null } else { [math]::Round(($normalizedAt - $NodeReadyAt).TotalSeconds, 3) }
        }
        throughput = [ordered]@{
            preFaultResultRps = $preFaultRps
            thresholdRps = if ($null -eq $preFaultRps) { $null } else { $ThroughputRecoveryRatio * $preFaultRps }
            ratio = $ThroughputRecoveryRatio
            windowSeconds = $ThroughputWindowSeconds
            consecutiveWindows = $ThroughputConsecutiveWindows
            rollingSampleCount = $rolling.Count
            rollingSpanMaxSeconds = $rollingSpanMaxSeconds
            recoveredAt = if ($null -eq $recoveredAt) { $null } else { $recoveredAt.ToString("o") }
            secondsAfterFault = if ($null -eq $recoveredAt) { $null } else { [math]::Round(($recoveredAt - $FaultAt).TotalSeconds, 3) }
            secondsAfterNodeReady = if ($null -eq $recoveredAt -or $null -eq $NodeReadyAt) { $null } else { [math]::Round(($recoveredAt - $NodeReadyAt).TotalSeconds, 3) }
            basis = "5s rolling result RPS from the same 1s samples, each window spanning between ${ThroughputWindowSeconds}s and ${MaxWindowSpanSeconds}s so a sampling hole cannot pass as a rate, held at or above ${ThroughputRecoveryRatio} of the pre-fault value for ${ThroughputConsecutiveWindows} consecutive windows after max(faultInjectedAt, nodeReadyAt)"
        }
        postRecoveryWindow = $postRecovery
        recoveryTimeout = ($null -eq $normalizedAt)
    }
}

function Step-FaultRecoverySample {
    # The measured phase's single sampling tick, reachable from inside a wait as well as from the main
    # loop. A fault run's backlog peak, its normalisation instant and its throughput recovery are all
    # read off this series, so it has to keep running while the loop is waiting on a restart or on a
    # readiness gate. A wait that owns the clock outright is a wait that leaves a hole exactly where
    # the recovery is: the first smoke run sampled until 3s before its kill and then not again for 140
    # seconds, which is why its backlog peak and normalisation could not be computed at all.
    $state = $script:faultSampling
    if ($null -eq $state) { return }
    $now = [datetimeoffset]::UtcNow
    if ($now -lt $state.nextTick) { return }
    Save-StaircaseBoundarySnapshots -Trace $state.trace -NowMillis $now.ToUnixTimeMilliseconds() -Captured $state.captured
    $sample = Save-StaircaseSample -Phase "load" -Trace $state.trace -ContestId $state.contestId
    $state.samples.Add([pscustomobject]@{
        at = $now; unfinished = $sample.unfinishedContest
        unapplied = $sample.unappliedContest; accepted = $sample.accepted; results = $sample.results
    })
    $state.nextTick = $state.nextTick.AddSeconds(1)
    if (([datetimeoffset]::UtcNow - $state.nextTick).TotalMilliseconds -gt 1000) { $state.nextTick = [datetimeoffset]::UtcNow }
}

function Invoke-FaultRecoveryPhase {
    param(
        [Parameter(Mandatory = $true)]$Process,
        [Parameter(Mandatory = $true)]$Trace,
        [Parameter(Mandatory = $true)][long]$ContestId,
        $CapturedBoundaries
    )
    # The measured phase of a fault-recovery run. Everything the experiment reports afterwards is
    # derived from this loop's own samples, so the loop is written deadline-first: the trigger, the
    # restart deadline and the readiness gates are all evaluated before the sampler is allowed to run,
    # and the wait for the restart deadline performs no I/O of any kind. A sampler tick costs most of
    # a second, and letting one sit between the clock and a deadline is exactly how a measured 15s
    # outage becomes an unmeasured 17s one.
    $killedPort = if ($KilledNode -eq "judge-1") { 19001 } else { 19002 }
    $measurementStartedAt = [datetimeoffset]::Parse($events.measurementStartedAt)
    # The window opens on a clock; the kill does not. faultScheduledAt therefore means "the trigger
    # window opened", which is why it can be recorded before any sample exists.
    $windowOpenAt = $measurementStartedAt.AddSeconds($FaultMinSteadySeconds)
    $triggerWaitEndsAt = $windowOpenAt.AddSeconds($FaultTriggerWaitSeconds)
    $events.faultScheduledAt = $windowOpenAt.ToString("o")

    $samples = New-Object System.Collections.Generic.List[object]
    $injected = $false
    $escalation = $null
    $triggerRecord = $null
    $activeWorkSeen = $false
    $restartRequested = $false
    $restartScheduledAt = $null
    $downObserveUntil = $null
    $nodeReadyChecked = $false
    $nodeReadyDetail = $null
    $nodeReadyFailure = $null
    $killSnapshot = $null
    $downWindowObservationCount = 0
    $nextTick = [datetimeoffset]::UtcNow
    $script:staircaseLastTickUtc = $null
    # The sampling tick is published to script scope so the waiting helpers can keep the series alive
    # while they wait. The list is the same object this function reads at the end, so a sample taken
    # inside a wait is in this function's series afterwards. nextTick lives here rather than in a local
    # so the loop and the waits cannot drift apart on when the next tick is due.
    $script:faultSampling = [ordered]@{
        samples = $samples
        nextTick = $nextTick
        trace = $Trace
        contestId = $ContestId
        captured = $CapturedBoundaries
    }
    Write-Host ("[fault] measurement started $($events.measurementStartedAt); the trigger window opens at " +
        "$($windowOpenAt.ToString('o')) (measurement + ${FaultMinSteadySeconds}s) and is polled until $($triggerWaitEndsAt.ToString('o'))")

    while (-not $Process.HasExited) {
        $now = [datetimeoffset]::UtcNow

        # ---- A. trigger evaluation, before any sampling ----
        if (-not $injected -and $null -eq $escalation -and $now -ge $windowOpenAt) {
            $gaugeAt = [datetimeoffset]::UtcNow
            $judge1 = Get-JudgeGauges -Port 19001
            $judge2 = Get-JudgeGauges -Port 19002
            # Float styles, because Micrometer prints these gauges as "16.0": a decimal point on a
            # value that is always an integer. Parsing them with Integer styles returns null for every
            # reading, and null is defined here to mean "this node could not be read", so an
            # integer-only parse turned a node with six in-flight judgements into no active work - the
            # trigger recorded escalation "no-active-work" while its own snapshot showed running 6.0 and
            # reserved 6.0, and every run would have been refused as faulted without active work.
            $running1 = ConvertTo-DoubleOrNull $judge1.running
            $reserved1 = ConvertTo-DoubleOrNull $judge1.reserved
            $running2 = ConvertTo-DoubleOrNull $judge2.running
            $reserved2 = ConvertTo-DoubleOrNull $judge2.reserved
            # An unreachable node's gauge is absent, not zero, so it cannot be read as "no work".
            $values = @(@($running1, $reserved1, $running2, $reserved2) | Where-Object { $null -ne $_ -and $_ -ge 1 })
            if ($values.Count -gt 0) { $activeWorkSeen = $true }
            $onTarget = ($null -ne $running1 -and $running1 -ge $FaultMinRunning -and $null -ne $reserved1 -and $reserved1 -ge $FaultMinReserved)
            $fallback = ($null -ne $running1 -and $running1 -ge $FaultMinRunning -and $null -ne $reserved1 -and $reserved1 -ge $FaultFallbackMinReserved)
            if ($onTarget) {
                $escalation = "reserved>=$FaultMinReserved"
            } elseif ($now -ge $triggerWaitEndsAt) {
                $escalation = if ($fallback) { "reserved>=$FaultFallbackMinReserved-fallback" }
                    elseif (-not $activeWorkSeen) { "no-active-work" }
                    else { "window-elapsed-below-primary-threshold" }
            }
            if ($null -ne $escalation) {
                $triggerRecord = [ordered]@{
                    windowOpenedAt = $windowOpenAt.ToString("o")
                    observedAt = $gaugeAt.ToString("o")
                    escalation = $escalation
                    waitedSeconds = [math]::Round(($gaugeAt - $windowOpenAt).TotalSeconds, 3)
                    primaryCondition = "running >= $FaultMinRunning and reserved >= $FaultMinReserved"
                    fallbackCondition = "running >= $FaultMinRunning and reserved >= $FaultFallbackMinReserved once the window elapses"
                    primaryMinRunning = $FaultMinRunning
                    primaryMinReserved = $FaultMinReserved
                    fallbackMinReserved = $FaultFallbackMinReserved
                    judge1 = @{ running = $judge1.running; reserved = $judge1.reserved; queued = $judge1.queued }
                    judge2 = @{ running = $judge2.running; reserved = $judge2.reserved; queued = $judge2.queued }
                    # The raw gauge text above is the evidence; these are the numbers the decision was
                    # actually made from, so the escalation can be re-checked against them.
                    thresholdReadings = [ordered]@{
                        judge1Running = $running1; judge1Reserved = $reserved1
                        judge2Running = $running2; judge2Reserved = $reserved2
                        basis = "parsed from the raw gauge text with Float styles; an unreadable gauge is null, never 0"
                    }
                    activeWorkSeen = $activeWorkSeen
                    faultNotInjectedWithActiveWork = (-not $activeWorkSeen)
                }
                Write-Host ("[fault] trigger fired at $($gaugeAt.ToString('o')): escalation=$escalation " +
                    "judge1 running=$running1 reserved=$reserved1 queued=$($judge1.queued) " +
                    "judge2 running=$running2 reserved=$reserved2 (waited $($triggerRecord.waitedSeconds)s)")
            }
        }

        # ---- B. injection, once, still before any sampling ----
        if (-not $injected -and $null -ne $escalation) {
            $preFaultStartedAt = [datetimeoffset]::UtcNow
            Save-MetricsSnapshot "pre-fault"
            $events.preFaultSnapshotSeconds = [math]::Round(([datetimeoffset]::UtcNow - $preFaultStartedAt).TotalSeconds, 3)
            $staleBefore = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(o.attempts - 1, 0)), 0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.contestId)"
            $events.staleAttemptsBeforeFault = if ($null -eq $staleBefore) { 0 } else { $staleBefore }
            Invoke-Compose -Arguments @("kill", $KilledNode)
            $events.faultInjectedAt = [datetimeoffset]::UtcNow.ToString("o")
            $script:faultWasInjected = $true
            $injected = $true
            # Scheduled from the injection itself, not from the trigger's clock position, so a slow
            # pre-kill snapshot moves the whole outage rather than shortening it.
            $restartScheduledAt = [datetimeoffset]::Parse($events.faultInjectedAt).AddSeconds($DownDurationSeconds)
            $events.restartScheduledAt = $restartScheduledAt.ToString("o")
            $events.faultInjectionLagSeconds = [math]::Round(([datetimeoffset]::Parse($events.faultInjectedAt) - [datetimeoffset]::Parse($triggerRecord.observedAt)).TotalSeconds, 3)
            $events.faultNotInjectedWithActiveWork = (-not $activeWorkSeen)
            $events.killSnapshotAt = [datetimeoffset]::UtcNow.ToString("o")
            # The last two seconds before the restart belong to the clock alone, so any observation
            # that could cost more than that is kept out of them by construction.
            $downObserveUntil = $restartScheduledAt.AddSeconds(-2)
            # Taken after the kill: the cluster's state is frozen, so no query here can race the kill,
            # and the rows that are still PUBLISHING are exactly the ones the dead node was holding
            # or waiting on - which the schema cannot attribute to a node.
            $script:claimSnapshot = Save-ClaimSnapshot
            $killSnapshot = Save-FaultSnapshot -Trigger $triggerRecord -ClaimSnapshot $script:claimSnapshot
            # Positive means the kill snapshot started after the kill, which is the only order the
            # schema allows - the snapshot describes the post-kill cluster. The previous expression
            # subtracted the other way round, so a snapshot that started 0.68s after the kill was
            # reported as -0.68 seconds and read as if it had preceded it.
            $events.killToSnapshotStartSeconds = [math]::Round(
                (([datetimeoffset]::UtcNow.AddSeconds(-$killSnapshot.seconds)) - [datetimeoffset]::Parse($events.faultInjectedAt)).TotalSeconds, 3)
            Observe-FaultRecovery "fault"
            Write-Host ("[fault] SIGKILL $KilledNode at $($events.faultInjectedAt); kill snapshot started " +
                "$($events.killToSnapshotStartSeconds)s after the kill and took $($killSnapshot.seconds)s; " +
                "restart scheduled for $($restartScheduledAt.ToString('o'))")
        }

        # ---- C. down window: the deadline owns the clock, the sampler keeps recording ----
        if ($injected -and -not $restartRequested) {
            if ($now -lt $downObserveUntil) {
                # The outage is where the backlog peak is, so the 1s series continues across it. Only
                # the last two seconds belong to the clock alone: past downObserveUntil this branch takes
                # no sample and no reading, and waits on the deadline with no I/O of any kind.
                Step-FaultRecoverySample
                Observe-FaultRecovery "node-down"
                $downWindowObservationCount++
                $sliceEnd = $now.AddSeconds(1)
                if ($sliceEnd -gt $downObserveUntil) { $sliceEnd = $downObserveUntil }
                Wait-UntilDeadline -Deadline $sliceEnd
            } else {
                Wait-UntilDeadline -Deadline $restartScheduledAt
                $restartRequestedAt = [datetimeoffset]::UtcNow
                # Recorded before the docker CLI is invoked, so the CLI's own latency stays inside the
                # outage instead of inflating the measured down duration.
                $events.restartRequestedAt = $restartRequestedAt.ToString("o")
                $events.restartTimingErrorSeconds = [math]::Round(($restartRequestedAt - $restartScheduledAt).TotalSeconds, 3)
                $restartRequested = $true
                Invoke-Compose -Arguments @("start", $KilledNode)
                # Kept under the name the shared cohort labels already read.
                $events.nodeRestartedAt = [datetimeoffset]::UtcNow.ToString("o")
                Write-Host ("[fault] down window ended: restart requested at $($events.restartRequestedAt) " +
                    "($($events.restartTimingErrorSeconds)s from schedule), container started at $($events.nodeRestartedAt), " +
                    "observations during the down window: $downWindowObservationCount")
            }
            continue
        }

        # ---- D. the four readiness gates ----
        if ($restartRequested -and -not $nodeReadyChecked) {
            # These gates wait up to three minutes between them, and that wait is inside the recovery
            # window the recovery metrics are read from. The sampler is handed to them rather than left
            # behind: every gate checks its own deadline before polling, so a tick can delay the next
            # check but cannot move a deadline.
            Write-Host "[fault] waiting for $KilledNode to reach ready at $([datetimeoffset]::UtcNow.ToString('o'))"
            $gateProgress = [ordered]@{}
            try {
                $nodeReadyDetail = Wait-JudgeNodeReady -Node $KilledNode -Port $killedPort `
                    -ReadinessTimeoutSeconds 120 -DispatcherTimeoutSeconds 60 `
                    -OnPoll { Step-FaultRecoverySample } -Progress $gateProgress
                $events.containerRunningAt = $nodeReadyDetail.containerRunningAt.ToString("o")
                $events.nodeReadyAt = $nodeReadyDetail.nodeReadyAt.ToString("o")
                Save-MetricsSnapshot "post-restart"
                Write-Host ("[fault] $KilledNode ready at $($events.nodeReadyAt): container " +
                    "$($nodeReadyDetail.containerRunningAt.ToString('o')), readiness " +
                    "$($nodeReadyDetail.readinessAt.ToString('o')) after $($nodeReadyDetail.readinessProbeAttempts) probes, " +
                    "metrics $($nodeReadyDetail.metricsAt.ToString('o')), dispatcher " +
                    "$($nodeReadyDetail.dispatcherActiveAt.ToString('o'))")
            } catch {
                # A judge that never confirms its dispatcher is a finding, not a crash: the run keeps
                # sampling so its evidence survives, the anchors stay null, and the run is refused as a
                # recovery measurement at the end rather than being thrown away here. Whatever gates did
                # pass are kept, because they are the difference between "the container came back but
                # never served" and "the container never came back at all".
                $nodeReadyFailure = $_.Exception.Message
                $events.nodeReadyAt = $null
                if ($gateProgress.Contains("containerRunningAt")) {
                    $events.containerRunningAt = $gateProgress.containerRunningAt.ToString("o")
                }
                Write-Host "[fault] $KilledNode did not reach ready: $nodeReadyFailure"
            }
            $gateProgressText = [ordered]@{}
            foreach ($key in $gateProgress.Keys) { $gateProgressText[$key] = $gateProgress[$key].ToString("o") }
            $events.nodeReadyGateProgress = $gateProgressText
            $nodeReadyChecked = $true
            continue
        }

        # ---- E. sampling ----
        Step-FaultRecoverySample

        # ---- F. sleep to the earliest thing the loop is waiting for ----
        $wakeAt = $script:faultSampling.nextTick
        if (-not $injected -and $null -eq $escalation -and $wakeAt -gt $windowOpenAt) { $wakeAt = $windowOpenAt }
        if ($injected -and -not $restartRequested -and $wakeAt -gt $restartScheduledAt) { $wakeAt = $restartScheduledAt }
        $remaining = ($wakeAt - [datetimeoffset]::UtcNow).TotalMilliseconds
        if ($remaining -gt 0) {
            $slice = [int][math]::Min(200, [math]::Ceiling($remaining))
            if ($slice -lt 1) { $slice = 1 }
            Start-Sleep -Milliseconds $slice
        }
    }

    $events.downWindowObservationCount = $downWindowObservationCount
    $events.nodeReadyGateSatisfied = if ($null -eq $events.nodeReadyAt) { $false } else { $true }
    $events.nodeReadyFailure = $nodeReadyFailure
    # The state object dies with the phase, so a later mode cannot sample against a finished run.
    $script:faultSampling = $null

    $recovery = $null
    if ($injected) {
        $faultAt = [datetimeoffset]::Parse($events.faultInjectedAt)
        $nodeReadyAt = if ($null -eq $events.nodeReadyAt) { $null } else { [datetimeoffset]::Parse($events.nodeReadyAt) }
        # The pre-fault steady window is fixed at 30s before the fault wherever the trigger window was
        # allowed to open, because the analyzer baselines the same 30s. Keying this on the parameter
        # instead made the harness and the analyzer disagree on the window - and so on the derived
        # instant - in any run whose trigger window was not 30s.
        $recovery = Get-FaultRecoveryDerived -Samples ($samples.ToArray()) -FaultAt $faultAt -NodeReadyAt $nodeReadyAt `
            -PreFaultSeconds 30
        $events.throughputRecoveredAt = $recovery.throughput.recoveredAt
        $events.backlogNormalizedAt = $recovery.normalization.backlogNormalizedAt
        $events.judgeBacklogNormalizedAt = $recovery.normalization.judgeBacklogNormalizedAt
        $events.scoreboardBacklogNormalizedAt = $recovery.normalization.scoreboardBacklogNormalizedAt
        $events.recoveryTimeout = $recovery.recoveryTimeout
        $events.postRecoveryWindow = $recovery.postRecoveryWindow
        Write-Host ("[fault] phase ended: $($samples.Count) samples, backlog peak observed, judge " +
            "normalised $(if ($null -eq $recovery.normalization.judgeBacklogNormalizedAt) { 'never' } else { $recovery.normalization.judgeBacklogNormalizedAt }), " +
            "scoreboard $(if ($null -eq $recovery.normalization.scoreboardBacklogNormalizedAt) { 'never' } else { $recovery.normalization.scoreboardBacklogNormalizedAt }), " +
            "throughput $(if ($null -eq $recovery.throughput.recoveredAt) { 'never' } else { $recovery.throughput.recoveredAt }), " +
            "recoveryTimeout=$($recovery.recoveryTimeout)")
    } else {
        $events.recoveryTimeout = $null
    }
    ($samples | ForEach-Object {
        [pscustomobject]@{
            at = $_.at.ToString("o"); judgeBacklog = $_.unfinished
            scoreboardPending = $_.unapplied; accepted = $_.accepted; results = $_.results
        }
    }) | Export-Csv (Join-Path $runDirectory "recovery-samples.csv") -NoTypeInformation -Encoding utf8

    return [pscustomobject]@{
        injected = $injected
        escalation = $escalation
        trigger = $triggerRecord
        killSnapshot = $killSnapshot
        nodeReady = $nodeReadyDetail
        nodeReadyFailure = $nodeReadyFailure
        recovery = $recovery
        downWindowObservationCount = $downWindowObservationCount
        sampleCount = $samples.Count
    }
}

$env:CONTEST_JUDGE_DISPATCH_MODE = $DispatchMode
$env:CONTEST_JUDGE_CONCURRENCY = "$WorkerCount"
$env:CONTEST_JUDGE_PREFETCH = "$RabbitPrefetch"
$env:CONTEST_JUDGE_MYSQL_WORKERS = "$WorkerCount"
$env:CONTEST_JUDGE_MYSQL_CLAIM_BATCH_SIZE = "$MySqlClaimBatchSize"
$env:CONTEST_JUDGE_MYSQL_MAX_IN_FLIGHT = "$MySqlMaxInFlight"
$env:CONTEST_JUDGE_MYSQL_CLAIM_TIMEOUT = $claimTimeoutProperty
$env:CONTEST_JUDGE_MYSQL_POLL_INTERVAL = $MySqlPollInterval
$env:JUDGE_LATENCY_ENABLED = "true"
$env:JUDGE_LATENCY_SEED = "$LatencySeed"
$env:JUDGE_LATENCY_KEY_SOURCE = "code"
$env:JUDGE_BASE_MILLIS = "50"
$env:JUDGE_SLOW_MILLIS = "2000"
$env:JUDGE_SLOW_RATIO = "0.05"
$env:CONTEST_RATE_LIMIT_STORE = "redis"
$env:CONTEST_RATE_LIMIT_COOLDOWN_MILLIS = "2000"

# Declared before the try so the failure path can tell "Gatling never started" from "it started and
# the artifacts are missing" instead of passing $null to a [datetime] parameter and losing the
# original exception behind a binding error.
$gatlingStarted = $null

if ($DryRun) {
    Invoke-Compose -Arguments @("config") | Set-Content (Join-Path $runDirectory "compose-config.yaml") -Encoding utf8
    "Dry run only; no containers or load were started." | Set-Content (Join-Path $runDirectory "DRY_RUN.txt") -Encoding utf8
    if ($stagedLoad) {
        # The boundaries themselves come from the JVM trace at run time; this only states the
        # shape the parameters imply, so a wrong ladder is caught before the stack is built.
        $expectedPlan | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "expected-plan.json") -Encoding utf8
        if ($NormalTimeout) {
            Write-Host "Normal timeout: warm-up at $warmupPhaseRps RPS for ${WarmupSeconds}s in '$warmupPrefix' (population $warmupPopulation), full drain, then measurement at $TargetRps RPS for ${MeasurementSeconds}s in '$measurementPrefix' (hold ${effectiveHoldSeconds}s = measurement + ${SteadyGuardSeconds}s guard), claim timeout $MySqlClaimTimeout, total $($expectedPlan.totalSeconds)s, population $($expectedPlan.population)."
            if ($AuthPrepSeconds -gt 0) {
                Write-Host "Session preparation: each phase's population logs in across its ${AuthPrepSeconds}s ramp (~$([math]::Round($measurementPopulation / $AuthPrepSeconds, 1)) logins/s) and no submission is sent until the gate at the ramp's end; the measured window of $($MeasurementSeconds)s opens $($SteadyGuardSeconds)s after that."
            }
            if ($IngressPreflight) {
                Write-Host "Ingress preflight: before the warm-up, $measurementPopulation sessions established over ${AuthPrepSeconds}s in '$preflightPrefix' (its own seeded contest), held $($PreflightHoldSeconds)s, drained to quiescence; a single refusal at the published port stops the run."
            }
        } elseif ($FaultRecovery) {
            # One contiguous string rather than a `+` join: the earlier split landed the operator inside
            # the first fragment's quotes, so the message printed the window as "from 30 + s" instead of
            # "from 30s". The plan a reader is checking before the stack is built is exactly where a
            # mangled number is least affordable.
            Write-Host ("Fault recovery: warm-up at $TargetRps RPS for ${WarmupSeconds}s in '$warmupPrefix', full drain, " +
                "then measurement at $TargetRps RPS for ${MeasurementSeconds}s in '$measurementPrefix' (hold ${effectiveHoldSeconds}s). " +
                "SIGKILL $KilledNode once it holds running >= $FaultMinRunning and reserved >= $FaultMinReserved, " +
                "from ${FaultMinSteadySeconds}s into the measured window and at the latest " +
                "$($FaultMinSteadySeconds + $FaultTriggerWaitSeconds)s into it (fallback reserved >= $FaultFallbackMinReserved). " +
                "Down for exactly ${DownDurationSeconds}s, then restart with identical settings and observe. " +
                "Claim timeout $MySqlClaimTimeout, max-in-flight $MySqlMaxInFlight, total $($expectedPlan.totalSeconds)s, " +
                "population $($expectedPlan.population), seconds left after a worst-case restart " +
                "$($expectedPlan.faultRecovery.secondsLeftAfterWorstCaseRestart).")
        } else {
            Write-Host "Staircase: $($stageRpsList -join ',') RPS, warm-up stages $WarmupStageCount, hold ${StageHoldSeconds}s, guard ${SteadyGuardSeconds}s, total $($expectedPlan.totalSeconds)s, top population $($expectedPlan.maxConcurrentUsers)."
        }
    }
    Write-Host "Dry run valid. Parameters and rendered Compose config: $runDirectory"
    exit 0
}

$events = [ordered]@{ runStartedAt=$null; loadStartedAt=$null; faultScheduledAt=$null; faultInjectedAt=$null; faultTimingErrorSeconds=$null; staleAttemptsBeforeFault=0; firstStaleReclaimObservedAt=$null; restartRequestedAt=$null; nodeRestartedAt=$null; nodeReadyAt=$null; loadEndedAt=$null; runEndedAt=$null; contestId=$null; measurementBaselineAt=$null; measurementEndSnapshotAt=$null }
$events.warmupEndedAt = $null; $events.measurementStartedAt = $null
$events.drainStartedAt = $null; $events.drainEndedAt = $null; $events.drainSeconds = $null
$events.traceAnchorUtc = $null; $events.tracePlanEndUtc = $null; $events.stageWindowAlignment = $null
$events.stageWindowAlignmentErrorSeconds = $null; $events.gatlingExitCode = $null; $events.gatlingAssertionFailed = $false
$events.warmupContestId = $null; $events.warmupPhaseStartedAt = $null; $events.warmupPhaseEndedAt = $null
$events.warmupQuiescedAt = $null; $events.warmupQuiescenceSeconds = $null; $events.warmupGatlingExitCode = $null
# Ingress preflight anchors. They stay null on a run that did not ask for the preflight, which is what
# distinguishes "the ingress was checked and carried this arrival pattern" from "it was not checked".
$events.preflightContestId = $null; $events.preflightStartedAt = $null; $events.preflightFinishedAt = $null
$events.preflightVerdict = $null; $events.preflightRefusals = $null; $events.preflightLoginsInHold = $null
$events.preflightSessionsStartedAfterGate = $null; $events.preflightProbeStatus = $null
# Login/submission separation, read off the measured phase's own request log. A null here on a run
# that had no separation says "not asked", and a zero says "asked and none were found"; the two are
# different findings and the run records which one it is.
$events.measurementLoginRequests = $null; $events.measurementLoginKoConnect = $null
$events.measurementSessionsStarted = $null; $events.measurementSubmitOffered = $null
$events.measurementSubmitKoConnect = $null; $events.measurementGateUtc = $null
$events.measurementSubmitOfferedInWindow = $null; $events.measurementWindowSeconds = $null
$events.measurementLoginRequestsInWindow = $null; $events.measurementLoginRequestsInHold = $null
$events.measurementSessionsStartedAfterGate = $null
$events.measurementFirstLoginUtc = $null; $events.measurementLastLoginUtc = $null
# Fault-recovery anchors. faultScheduledAt above is reused for the instant the trigger window opened,
# which is not a kill deadline; the kill happens when the target node is observed to hold work. These
# stay null in every other mode, where recoveryTimeout and the recovery block are not applicable.
$events.preFaultSnapshotSeconds = $null; $events.faultInjectionLagSeconds = $null
$events.faultNotInjectedWithActiveWork = $null; $events.killSnapshotAt = $null
$events.killToSnapshotStartSeconds = $null; $events.restartScheduledAt = $null
$events.restartTimingErrorSeconds = $null; $events.containerRunningAt = $null
$events.downWindowObservationCount = $null; $events.nodeReadyGateSatisfied = $null
$events.nodeReadyFailure = $null; $events.throughputRecoveredAt = $null
$events.backlogNormalizedAt = $null; $events.judgeBacklogNormalizedAt = $null
$events.scoreboardBacklogNormalizedAt = $null; $events.recoveryTimeout = $null
$events.postRecoveryWindow = $null
# How many sampler rows needed a retry before they were written. Counted rather than assumed to be
# zero: a run whose series was written a quarter of a second late reads differently from one that
# was written on the tick, and the count is the only way to tell them apart afterwards.
$events.sampleWriteRetries = 0
$script:sampleWriteRetries = 0
# Script scope rather than a local: the preflight starts its Gatling process inside its own function,
# and a failure raised from there must still leave the failure path the instant the log it read can be
# found by. This is what the phase's artifacts are located by after the fact.
$script:preflightPhaseStartedAt = $null
$staircaseTrace = $null; $staircaseStages = @(); $capturedBoundaries = @{}
$warmupSeed = $null; $warmupTrace = $null; $warmupStages = @(); $warmupQuiescence = $null
$warmupAcceptedAtBaseline = $null
$recoveryPhase = $null
$staircaseLastTickUtc = $null
$started = $false
$claimSnapshot = [pscustomobject]@{ exact=$false; ids=@(); observedActiveClaimCount=0 }
try {
    Invoke-Compose -Arguments @("config") | Set-Content (Join-Path $runDirectory "compose-config.yaml") -Encoding utf8
    Push-Location $repoRoot
    try {
        & .\gradlew.bat bootJar :gatling:prepareStandaloneGatling --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle preparation failed." }
    } finally { Pop-Location }
    $events.runStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    $started = $true
    Invoke-Compose -Arguments @("up", "-d", "--build")
    Wait-Healthy
    Invoke-Compose -Arguments @("restart", "nginx")
    Wait-Healthy
    if ($stagedLoad) {
        # Two contests inside one stack lifetime. The warm-up writes to one and the measurement to
        # the other, so no warm-up row can be counted inside a measured window. The duplicate
        # registry is keyed by (contestId, problemId, userId, codeHash), so the identical
        # user/problem/code workload can be replayed in the measurement contest without the second
        # phase being deduplicated away. Both contests are seeded up front: seeding between the
        # phases would put database work inside the run and move the load it is meant to precede.
        $seedBody = @{ userCount=$UserCount; problemCount=5; durationMinutes=60; reset=$true }
        $warmupSeed = Invoke-RestMethod -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" `
            -Body (@{ prefix=$warmupPrefix } + $seedBody | ConvertTo-Json -Compress) -TimeoutSec 60
        $seed = Invoke-RestMethod -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" `
            -Body (@{ prefix=$measurementPrefix } + $seedBody | ConvertTo-Json -Compress) -TimeoutSec 60
        if ([long]$warmupSeed.contestId -eq [long]$seed.contestId) {
            throw "The warm-up and measurement contests resolved to the same contest id, so the phases would not be isolated."
        }
        $events.warmupContestId = [long]$warmupSeed.contestId
        if ($IngressPreflight) {
            # Its own contest and its own user pool, so the sessions it establishes and the single
            # submission its login-plus-submit probe makes can be counted by nothing the measured
            # window reads. Seeded here with the other two because seeding later would put database
            # work inside the run it is meant to precede.
            $preflightSeed = Invoke-RestMethod -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" `
                -Body (@{ prefix=$preflightPrefix } + $seedBody | ConvertTo-Json -Compress) -TimeoutSec 60
            if ([long]$preflightSeed.contestId -eq [long]$seed.contestId -or [long]$preflightSeed.contestId -eq [long]$warmupSeed.contestId) {
                throw "The preflight contest resolved to the same contest id as a phase contest, so its work would not be isolated from the measurement."
            }
            $events.preflightContestId = [long]$preflightSeed.contestId
        }
    } else {
        $workloadPrefix = "tradeoff_seed_$LatencySeed"
        $seedRequest = @{ prefix=$workloadPrefix; userCount=$UserCount; problemCount=5; durationMinutes=60; reset=$true } | ConvertTo-Json -Compress
        $seed = Invoke-RestMethod -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" -Body $seedRequest -TimeoutSec 60
    }
    $events.contestId = [long]$seed.contestId
    $contestId = [long]$seed.contestId
    # In a phased-load run this first snapshot is the warm-up phase's starting point; the baseline
    # the measured window is read against is taken again once the warm-up has drained.
    Save-MetricsSnapshot $(if ($stagedLoad) { "warmup-start" } else { "start" })

    if ($stagedLoad) {
        # The per-second sampler reads these in the same statement as the backlog counts; prove
        # they are readable now rather than discovering it once a four minute load is under way.
        $probe = @(Invoke-SqlRows "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'")
        if ($probe.Count -eq 0) { throw "performance_schema.global_status is not readable; the staged sampler needs it." }
        # Every backlog number in this experiment is a difference, so nothing may be left over
        # from an earlier run: a stale row would be charged to the first stage's growth rate.
        $leftover = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
        if ($leftover -ne 0) { throw "contest_judge_outbox still holds $leftover unfinished rows from an earlier run." }
    }

    $classpath = (Get-Content (Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt") -Raw).Trim()
    $resultsFolder = Join-Path $repoRoot "gatling\build\reports\gatling"
    $tracePath = (Join-Path $runDirectory "stage-trace.csv") -replace '\\', '/'

    if ($stagedLoad -and $IngressPreflight) {
        # Before the warm-up rather than between the phases: the question is whether the fresh stack
        # can carry this experiment's arrival pattern at all, and asking it after the warm-up would
        # have spent the warm-up on an ingress that was already known to refuse connections. The
        # preflight's contest is drained to quiescence before this block returns, so the warm-up
        # starts against a stack with no work of the preflight's left in it.
        $events.preflightStartedAt = [datetimeoffset]::UtcNow.ToString("o")
        $preflight = Invoke-IngressPreflight -Seed $preflightSeed -TraceFile (Join-Path $runDirectory "preflight-stage-trace.csv")
        $events.preflightFinishedAt = [datetimeoffset]::UtcNow.ToString("o")
        $events.preflightVerdict = $preflight.verdict
        $events.preflightRefusals = $preflight.refusalTotal
        $events.preflightLoginsInHold = $preflight.sessions.loginsInHold
        $events.preflightSessionsStartedAfterGate = $preflight.sessions.startedAfterGate
        $events.preflightProbeStatus = "$($preflight.probe.loginStatus)/$($preflight.probe.submissionStatus)"
        Write-Host "Ingress preflight passed: $($preflight.population) sessions prepared over $($preflight.preparationSeconds)s, $($preflight.http.loginOffered) logins and $($preflight.http.submitOffered) submissions offered with $($preflight.refusalTotal) refusals."
    }

    if ($stagedLoad) {
        # Warm-up phase. It is offered the same rate and the same workload as the measurement and
        # differs only in which contest it writes to, so the measurement starts against a stack that
        # has already been through the same code paths (JIT, connection pool, buffer pool). Its
        # results are never part of the measured aggregates: the measured contest is a different
        # contest, so nothing it does can be counted by any query scoped to the measurement.
        $warmupTracePath = (Join-Path $runDirectory "warmup-stage-trace.csv") -replace '\\', '/'
        $events.warmupPhaseStartedAt = [datetimeoffset]::UtcNow.ToString("o")
        $warmupPhase = Start-GatlingLoadPhase -PhaseName "warmup" -Seed $warmupSeed -UserPrefix $warmupPrefix `
            -Rps $warmupPhaseRps -HoldSeconds $WarmupSeconds -TracePath $warmupTracePath
        $warmupStarted = $warmupPhase.startedAt
        $warmupTrace = Get-StaircaseTrace -Path (Join-Path $runDirectory "warmup-stage-trace.csv")
        if ($null -eq $warmupTrace) {
            throw "The warm-up phase's trace file is missing or incomplete, so its window cannot be placed."
        }
        $warmupStages = @(Get-StaircaseStages -Trace $warmupTrace)
        Wait-GatlingWithSamples -Process $warmupPhase.process -Trace $warmupTrace -ContestId $events.warmupContestId -Phase "warmup"
        $warmupPhase.process.WaitForExit()
        $events.warmupGatlingExitCode = $warmupPhase.process.ExitCode
        $events.warmupPhaseEndedAt = [datetimeoffset]::UtcNow.ToString("o")
        $events.warmupEndedAt = $events.warmupPhaseEndedAt
        # The warm-up has no separate report requirement: its only job is to have exercised the
        # stack, and its HTTP outcomes are recorded in its own gatling log. A non-zero exit is
        # recorded rather than fatal, but a *missing* report would make that log unreadable.
        if ($null -eq (Find-GatlingReport -StartedAt $warmupStarted)) {
            throw "The warm-up phase produced no Gatling report, so its log cannot be inspected."
        }
        Copy-GatlingArtifacts -StartedAt $warmupStarted -NamePrefix "warmup-" | Out-Null
        Export-GatlingPerSecond -Rows (Get-GatlingRequestRows -Path (Join-Path $runDirectory "warmup-gatling-simulation.log")) `
            -Path (Join-Path $runDirectory "warmup-requests-1s.csv") | Out-Null
        # The measured window's counters are taken as a delta from the scrape below, so the warm-up
        # must be fully finished - judged, applied and no worker still inside a judge call - before
        # it is taken. Otherwise warm-up work is charged to the measured phase.
        $warmupQuiescence = Wait-PipelineQuiescent -ContestId $events.warmupContestId -TimeoutSeconds 300 -Purpose "the warm-up contest" -AllowMissingExecutorGauges:($DispatchMode -eq "rabbit")
        if (-not $warmupQuiescence.quiescent) {
            throw "The warm-up phase never reached quiescence ($($warmupQuiescence.reason)); without it the measured window has no clean baseline."
        }
        $events.warmupQuiescedAt = [datetimeoffset]::UtcNow.ToString("o")
        $events.warmupQuiescenceSeconds = $warmupQuiescence.seconds
        Save-MetricsSnapshot "start"
        $events.measurementBaselineAt = [datetimeoffset]::UtcNow.ToString("o")
        $warmupAcceptedAtBaseline = $warmupQuiescence.accepted
    }

    $javaArgs = @(
        "-Xms256m", "-Xmx1g", "-Dperf.baseUrl=$baseUrl", "-Dperf.assert.minRequests=1",
        "-Dperf.assert.minSuccessPercent=$assertMinSuccess", "-Dperf.assert.p95Millis=$AssertP95Millis",
        "-Dperf.submitIntervalMillis=3100", "-Dperf.userPrefix=$workloadPrefix", "-Dperf.workloadSeed=$LatencySeed",
        "-Dperf.userIndex.start=1", "-Dperf.userIndex.end=$UserCount",
        "-Dperf.contestId=$($seed.contestId)", "-Dperf.problemId.start=$($seed.firstProblemId)", "-Dperf.problemId.end=$($seed.lastProblemId)"
    )
    if ($stagedLoad) {
        $javaArgs += @(
            "-Dperf.rampSeconds=$RampSeconds", "-Dperf.stepHoldSeconds=$effectiveHoldSeconds",
            "-Dperf.stageRps=$($stageRpsList -join ',')", "-Dperf.warmupStageCount=$WarmupStageCount",
            "-Dperf.authPrepSeconds=$AuthPrepSeconds",
            "-Dperf.stageTraceFile=$tracePath",
            "-cp", $classpath, "io.gatling.app.Gatling", "-s", "my.oj.perf.ContestSubmissionStepLoadSimulation"
        )
    } else {
        $javaArgs += @(
            "-Dperf.targetRps=$TargetRps", "-Dperf.rampSeconds=$RampSeconds", "-Dperf.holdSeconds=$DurationSeconds",
            "-cp", $classpath, "io.gatling.app.Gatling", "-s", "my.oj.perf.ContestSubmissionSimulation"
        )
    }
    $javaArgs += @("-rf", $resultsFolder, "-rd", "mysql-judge-tradeoff-$RunId")
    $gatlingStarted = Get-Date
    # Start-Process -PassThru -NoNewWindow hands back a Process whose ExitCode stays empty on this
    # PowerShell 5.1 even after WaitForExit(), which made the assertion check below read $null and
    # fail every run with "Gatling exited with code .". System.Diagnostics.Process populates it, and
    # UseShellExecute = $false without output redirection still lets Gatling write to this console.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = (Get-Command java.exe).Source
    $startInfo.UseShellExecute = $false
    $startInfo.Arguments = (($javaArgs | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' ')
    $gatling = [System.Diagnostics.Process]::Start($startInfo)
    $script:lastGatlingProcess = $gatling
    # Java/Gatling startup can take longer than a short fault offset. Anchor the
    # experiment clock to the first persisted submission, not process creation.
    $loadStartDeadline = (Get-Date).AddSeconds(60)
    do {
        $startedSubmissions = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($seed.contestId)"
        if ($startedSubmissions -gt 0) { break }
        if ($gatling.HasExited) { throw "Gatling exited before the first submission was persisted." }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $loadStartDeadline)
    if ($startedSubmissions -eq 0) { throw "No submission was persisted within 60 seconds of starting Gatling." }
    $events.loadStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    Save-CapacitySample "load-start"
    if ($FaultEnabled) {
        $faultDeadline = (Get-Date).AddSeconds($FaultAtSeconds)
        $events.faultScheduledAt = $faultDeadline.ToUniversalTime().ToString("o")
        # Leave a guard window for the synchronous pre-kill metric scrape. Without
        # it, probe latency itself moves a short configured fault several seconds.
        $captureDeadline = $faultDeadline.AddSeconds(-5)
        while ((Get-Date) -lt $captureDeadline -and -not $gatling.HasExited) {
            Save-BacklogSample "pre-fault" | Out-Null
            Save-CapacitySample "pre-fault"
            $remainingMillis = [math]::Floor(($captureDeadline - (Get-Date)).TotalMilliseconds)
            if ($remainingMillis -gt 0) { Start-Sleep -Milliseconds ([math]::Min(1000, $remainingMillis)) }
        }
        Save-MetricsSnapshot "pre-fault"
        $events.staleAttemptsBeforeFault = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(o.attempts - 1, 0)), 0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.contestId)"
        $remainingMillis = [math]::Floor(($faultDeadline - (Get-Date)).TotalMilliseconds)
        if ($remainingMillis -gt 0) { Start-Sleep -Milliseconds $remainingMillis }
        Invoke-Compose -Arguments @("kill", $KilledNode)
        $events.faultInjectedAt = [datetimeoffset]::UtcNow.ToString("o")
        $events.faultTimingErrorSeconds = [math]::Round(((Get-Date) - $faultDeadline).TotalSeconds, 3)
        $downDeadline = (Get-Date).AddSeconds($DownDurationSeconds)
        # Capture after kill so synchronous SQL inspection cannot postpone the
        # fault. Without claimed_by this remains an all-node active upper bound.
        $claimSnapshot = Save-ClaimSnapshot
        Observe-FaultRecovery "fault"
        while ((Get-Date) -lt $downDeadline) {
            Observe-FaultRecovery "node-down"
            $remainingMillis = [math]::Floor(($downDeadline - (Get-Date)).TotalMilliseconds)
            if ($remainingMillis -gt 0) { Start-Sleep -Milliseconds ([math]::Min(1000, $remainingMillis)) }
        }
        $events.restartRequestedAt = [datetimeoffset]::UtcNow.ToString("o")
        Invoke-Compose -Arguments @("start", $KilledNode)
        $events.nodeRestartedAt = [datetimeoffset]::UtcNow.ToString("o")
        Wait-Healthy -ObserveRecovery
        Wait-JudgeMetrics $KilledNode -ObserveRecovery
        $events.nodeReadyAt = [datetimeoffset]::UtcNow.ToString("o")
        Save-MetricsSnapshot "post-restart"
    }
    if ($FaultEnabled) {
        while (-not $gatling.HasExited) {
            Observe-FaultRecovery "post-restart"
            Save-CapacitySample "post-restart"
            Start-Sleep -Seconds 1
        }
        Observe-FaultRecovery "load-end"
    } elseif ($stagedLoad) {
        # Each round trip costs most of a second, so a loop that sleeps a further full second
        # samples at about 1.5s. This one sleeps only up to the next tick and records the period
        # it actually achieved, which is what the per-stage windows are read against.
        $staircaseTrace = Get-StaircaseTrace -Path (Join-Path $runDirectory "stage-trace.csv")
        if ($null -eq $staircaseTrace) {
            throw "The staircase trace file is missing or incomplete, so stage boundaries cannot be placed."
        }
        $staircaseStages = Get-StaircaseStages -Trace $staircaseTrace
        # Kept under its own name: in a normal-timeout run $warmupStages already holds the warm-up
        # phase's stages from its own trace, and this filter would empty it, because the measured
        # phase's trace has warmupStageCount 0 and therefore no warm-up stage to find.
        $traceWarmupStages = @($staircaseStages | Where-Object { $_.isWarmup })
        if ($traceWarmupStages.Count -gt 0) {
            $events.warmupEndedAt = [datetimeoffset]::FromUnixTimeMilliseconds($traceWarmupStages[-1].endMillis).ToString("o")
        }
        $measuredStages = @($staircaseStages | Where-Object { -not $_.isWarmup })
        if ($measuredStages.Count -gt 0) {
            $events.measurementStartedAt = [datetimeoffset]::FromUnixTimeMilliseconds($measuredStages[0].measurementStartMillis).ToString("o")
        }
        $events.traceAnchorUtc = [datetimeoffset]::FromUnixTimeMilliseconds($staircaseTrace.anchorMillis).ToString("o")
        $events.tracePlanEndUtc = [datetimeoffset]::FromUnixTimeMilliseconds($staircaseTrace.planEndMillis).ToString("o")

        $script:staircaseLastTickUtc = $null
        if ($FaultRecovery) {
            # The measured phase's tick loop is replaced wholesale: it has to interleave deadline work
            # with sampling, and the staged loop's "sleep only up to the next tick" rule has no notion
            # of a deadline that must not be overshot. Both loops write the same timeseries.csv columns
            # through Save-StaircaseSample, so the analyzer's windows and deltas are unaffected.
            $recoveryPhase = Invoke-FaultRecoveryPhase -Process $gatling -Trace $staircaseTrace `
                -ContestId $contestId -CapturedBoundaries $capturedBoundaries
        } else {
        $nextTick = [datetimeoffset]::UtcNow
        while (-not $gatling.HasExited) {
            $nextTick = $nextTick.AddSeconds(1)
            $nowMillis = [datetimeoffset]::UtcNow.ToUnixTimeMilliseconds()
            Save-StaircaseBoundarySnapshots -Trace $staircaseTrace -NowMillis $nowMillis -Captured $capturedBoundaries
            Save-StaircaseSample -Phase "load" -Trace $staircaseTrace -ContestId $contestId | Out-Null
            $remaining = ($nextTick - [datetimeoffset]::UtcNow).TotalMilliseconds
            if ($remaining -gt 0) {
                Start-Sleep -Milliseconds ([math]::Round($remaining))
            } elseif ($remaining -lt -1000) {
                # A whole period behind: re-anchor instead of firing a burst of catch-up samples.
                $nextTick = [datetimeoffset]::UtcNow
            }
        }
        }
    } else {
        while (-not $gatling.HasExited) {
            Save-BacklogSample "load" | Out-Null
            Save-CapacitySample "load"
            Start-Sleep -Seconds 1
        }
    }
    $gatling.WaitForExit()
    if ($stagedLoad) {
        # Capture a final boundary that may have landed between the last sampler tick and process
        # exit. Missing it would make all counter deltas for the final hold unavailable.
        Save-StaircaseBoundarySnapshots -Trace $staircaseTrace `
            -NowMillis ([datetimeoffset]::UtcNow.ToUnixTimeMilliseconds()) -Captured $capturedBoundaries
    }
    $events.loadEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events.gatlingExitCode = $gatling.ExitCode
    # An unreadable exit code means the outcome is unknown, which is neither a pass nor a failure.
    if ($null -eq $gatling.ExitCode) { throw "Gatling's exit code could not be read after it exited; the run's outcome is unknown." }
    if ($gatling.ExitCode -ne 0) {
        # Exit 2 is an assertion failure, and the report is written before assertions are
        # evaluated. A staged load deliberately drives the stack into overload, so the measurement
        # is still valid and its HTTP outcomes are accounted per phase; a missing report is not
        # an assertion failure and stays fatal.
        $reportLog = if ($stagedLoad -and $gatling.ExitCode -eq 2) { Find-GatlingReport -StartedAt $gatlingStarted } else { $null }
        if ($null -ne $reportLog -and (Test-Path (Join-Path $reportLog.Directory.FullName "js\global_stats.json"))) {
            $events.gatlingAssertionFailed = $true
            Write-Warning "Gatling reported an assertion failure (exit 2); keeping the run and reporting per-phase HTTP outcomes instead."
        } else {
            throw "Gatling exited with code $($gatling.ExitCode)."
        }
    }

    $events.drainStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSeconds)
    $backlog = $null
    do {
        if ($stagedLoad) {
            # This run's own work, not every contest row in the database.
            $drainSample = Save-StaircaseSample -Phase "drain" -Trace $staircaseTrace -ContestId $contestId
            # A tick whose counts did not arrive leaves the backlog unknown, not zero. Reading it as
            # zero would end the drain on an unmeasured sample and record a drained run, so the
            # backlog stays $null and the loop keeps polling; the loop's exit test below refuses to
            # call a run drained while the last reading is unknown.
            if ($null -eq $drainSample.unfinishedContest -or $null -eq $drainSample.unappliedContest -or
                $null -eq $drainSample.accepted -or $null -eq $drainSample.results) {
                $backlog = $null
            } else {
                # The two row counts alone are not a drain of the pipeline. Under rabbit a message
                # is PUBLISHED the moment the broker confirms it, so a queue full of messages the
                # judges have not reached yet is neither unfinished nor unapplied and both terms
                # read zero. A submission with no result row is exactly that in-flight work, so
                # results == accepted is what closes the gap - and it is the same invariant the
                # integrity check reports, which is why a drained run cannot disagree with it.
                $backlog = $drainSample.unfinishedContest + $drainSample.unappliedContest +
                    [math]::Max(0, $drainSample.accepted - $drainSample.results)
            }
        } else {
            $backlog = Save-BacklogSample "drain"
        }
        if ($null -ne $backlog -and $backlog -eq 0) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    $events.drainEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events.drainSeconds = [math]::Round(
        ([datetimeoffset]::Parse($events.drainEndedAt) - [datetimeoffset]::Parse($events.drainStartedAt)).TotalSeconds, 3)
    if ($null -eq $backlog) {
        throw "The drain gate never read the backlog: at least one tick returned no count, so this run's drain is undecided rather than complete."
    }
    if ($backlog -ne 0) { throw "Pipeline did not drain within $DrainTimeoutSeconds seconds." }
    Save-MetricsSnapshot "end"
    $events.measurementEndSnapshotAt = [datetimeoffset]::UtcNow.ToString("o")
    if ($stagedLoad) {
        # The measured window's judge-invocation delta is end minus start, so warm-up work that ran
        # after the baseline would be charged to the measurement. The baseline was taken at
        # quiescence and the warm-up contest's own submissions are the observable part of that work:
        # if any warm-up submission was persisted after the baseline, the delta is contaminated and
        # the run cannot be reported as a clean measurement.
        $warmupAcceptedAtEnd = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($events.warmupContestId)"
        if ($null -eq $warmupAcceptedAtEnd -or $null -eq $warmupAcceptedAtBaseline) {
            throw "The warm-up contest's submission count could not be read at both ends, so warm-up contamination of the measured window is undecided."
        }
        if ($warmupAcceptedAtEnd -ne $warmupAcceptedAtBaseline) {
            throw "The warm-up contest accepted $($warmupAcceptedAtEnd - $warmupAcceptedAtBaseline) more submissions after the baseline was taken, so the measured window's counter delta includes warm-up work."
        }
    }
    if ($stagedLoad) {
        Copy-GatlingArtifacts -StartedAt $gatlingStarted | Out-Null
        # The measured phase's own request stream, per second and per name. The analyzer's http-1s.csv
        # is the submit-only view its stage windows are read from; this is the raw one, and it is what
        # shows where the logins landed relative to the measured window - the separation this run
        # exists to demonstrate is a claim about two request names and cannot be read from a total.
        $measurementLogPath = Join-Path $runDirectory "gatling-simulation.log"
        $measurementRequests = Get-GatlingRequestRows -Path $measurementLogPath
        Export-GatlingPerSecond -Rows $measurementRequests -Path (Join-Path $runDirectory "requests-1s.csv") | Out-Null
        $measurementLogins = @($measurementRequests | Where-Object { $_.name -eq "api-login-once" })
        $measurementSubmits = @($measurementRequests | Where-Object { $_.name -eq "api-contest-submit" })
        $measurementSessions = Get-GatlingSessionRows -Path $measurementLogPath
        $measured = @($staircaseStages | Where-Object { -not $_.isWarmup })
        $events.measurementLoginRequests = $measurementLogins.Count
        $events.measurementLoginKoConnect = @($measurementLogins | Where-Object { $_.status -eq "ko-connect" }).Count
        $events.measurementSessionsStarted = $measurementSessions.starts.Count
        $events.measurementSubmitOffered = $measurementSubmits.Count
        $events.measurementSubmitKoConnect = @($measurementSubmits | Where-Object { $_.status -eq "ko-connect" }).Count
        $events.measurementGateUtc = $null
        $events.measurementLoginRequestsInWindow = $null
        $events.measurementLoginRequestsInHold = $null
        $events.measurementSessionsStartedAfterGate = $null
        $events.measurementFirstLoginUtc = $null; $events.measurementLastLoginUtc = $null
        # The count section 7's success criterion is judged on, taken directly from the request rows
        # rather than summed out of the one-second buckets: the window is ten seconds of client
        # request starts and it does not begin on a second boundary, so a bucket view would either
        # drop or double the ~1000 requests in the second it straddles.
        $events.measurementSubmitOfferedInWindow = $null
        $events.measurementWindowSeconds = $null
        if ($measured.Count -gt 0) {
            $measuredStage = $measured[0]
            $events.measurementGateUtc = [datetimeoffset]::FromUnixTimeMilliseconds($measuredStage.startMillis).ToString("o")
            $events.measurementWindowSeconds = [math]::Round(([long]$measuredStage.measurementEndMillis - [long]$measuredStage.measurementStartMillis) / 1000.0, 3)
            $events.measurementSubmitOfferedInWindow = @($measurementSubmits | Where-Object {
                $_.startMillis -ge [long]$measuredStage.measurementStartMillis -and $_.startMillis -lt [long]$measuredStage.measurementEndMillis
            }).Count
            $events.measurementLoginRequestsInWindow = @($measurementLogins | Where-Object {
                $_.startMillis -ge [long]$measuredStage.measurementStartMillis -and $_.startMillis -lt [long]$measuredStage.measurementEndMillis
            }).Count
            $events.measurementLoginRequestsInHold = @($measurementLogins | Where-Object {
                $_.startMillis -ge [long]$measuredStage.startMillis -and $_.startMillis -lt [long]$measuredStage.endMillis
            }).Count
            $events.measurementSessionsStartedAfterGate = @($measurementSessions.starts | Where-Object { $_ -ge [long]$measuredStage.startMillis }).Count
        }
        if ($measurementLogins.Count -gt 0) {
            $events.measurementFirstLoginUtc = [datetimeoffset]::FromUnixTimeMilliseconds(
                ($measurementLogins | Measure-Object -Property startMillis -Minimum).Minimum).ToString("o")
            $events.measurementLastLoginUtc = [datetimeoffset]::FromUnixTimeMilliseconds(
                ($measurementLogins | Measure-Object -Property startMillis -Maximum).Maximum).ToString("o")
        }
        # The predicted plan is only useful if it lands where the traffic actually ran. Gatling
        # stops the injector at maxDuration, so the last completed request is the observable end of
        # the schedule; anything larger than the tolerance means the windows are not trustworthy.
        $lastRequestMillis = Get-GatlingLastRequestMillis -StartedAt $gatlingStarted
        if ($null -eq $lastRequestMillis) {
            $events.stageWindowAlignment = "unavailable"
        } else {
            $alignmentError = [math]::Round(($staircaseTrace.planEndMillis - $lastRequestMillis) / 1000.0, 3)
            $events.stageWindowAlignmentErrorSeconds = $alignmentError
            $events.stageWindowAlignment = if ([math]::Abs($alignmentError) -le $TraceAlignmentToleranceSeconds) { "ok" } else { "degraded" }
        }

        $segmentsDocument = @($staircaseTrace.segments | ForEach-Object {
            [ordered]@{
                index = $_.index; kind = $_.kind; stageIndex = $_.stageIndex; isWarmup = $_.isWarmup
                population = $_.population; targetRps = $_.targetRps
                startMillis = $_.startMillis; endMillis = [long]$_.endMillis
                start = [datetimeoffset]::FromUnixTimeMilliseconds($_.startMillis).ToString("o")
                end = [datetimeoffset]::FromUnixTimeMilliseconds([long]$_.endMillis).ToString("o")
            }
        })
        $stagesDocument = @($staircaseStages | ForEach-Object {
            $startLabel = $_.prometheusStartLabel
            $measurementStartLabel = $_.prometheusMeasurementStartLabel
            $endLabel = $_.prometheusEndLabel
            [ordered]@{
                stageIndex = $_.stageIndex; label = $_.label; isWarmup = $_.isWarmup
                targetRps = $_.targetRps; population = $_.population
                traceSegmentIndex = $_.traceSegmentIndex
                startMillis = $_.startMillis; endMillis = $_.endMillis
                measurementStartMillis = $_.measurementStartMillis; measurementEndMillis = $_.measurementEndMillis
                start = [datetimeoffset]::FromUnixTimeMilliseconds($_.startMillis).ToString("o")
                end = [datetimeoffset]::FromUnixTimeMilliseconds($_.endMillis).ToString("o")
                measurementStart = [datetimeoffset]::FromUnixTimeMilliseconds($_.measurementStartMillis).ToString("o")
                measurementEnd = [datetimeoffset]::FromUnixTimeMilliseconds($_.measurementEndMillis).ToString("o")
                prometheusStartLabel = $startLabel
                prometheusMeasurementStartLabel = $measurementStartLabel
                prometheusEndLabel = $endLabel
                prometheusStartLagMs = if ($capturedBoundaries.ContainsKey($startLabel)) { $capturedBoundaries[$startLabel] - $_.startMillis } else { $null }
                prometheusMeasurementStartLagMs = if ($capturedBoundaries.ContainsKey($measurementStartLabel)) { $capturedBoundaries[$measurementStartLabel] - $_.measurementStartMillis } else { $null }
                prometheusEndLagMs = if ($capturedBoundaries.ContainsKey($endLabel)) { $capturedBoundaries[$endLabel] - $_.endMillis } else { $null }
            }
        })
        $warmupDocument = $null
        if ($stagedLoad) {
            $warmupDocument = [ordered]@{
                contestId = $events.warmupContestId
                contestPrefix = $warmupPrefix
                userPrefix = $warmupPrefix
                targetRps = $warmupPhaseRps
                population = $warmupPopulation
                holdSeconds = $WarmupSeconds
                traceAnchorUtc = [datetimeoffset]::FromUnixTimeMilliseconds($warmupTrace.anchorMillis).ToString("o")
                tracePlanEndUtc = [datetimeoffset]::FromUnixTimeMilliseconds($warmupTrace.planEndMillis).ToString("o")
                phaseStartedAt = $events.warmupPhaseStartedAt
                phaseEndedAt = $events.warmupPhaseEndedAt
                gatlingExitCode = $events.warmupGatlingExitCode
                quiescedAt = $events.warmupQuiescedAt
                quiescenceSeconds = $events.warmupQuiescenceSeconds
                quiescenceReason = $warmupQuiescence.reason
                acceptedAtBaseline = $warmupAcceptedAtBaseline
                # A nonzero value here would mean the baseline was contaminated; the run throws
                # before writing this file if it is nonzero, so zero is the only reachable value.
                acceptedGrowthAfterBaseline = $warmupAcceptedAtEnd - $warmupAcceptedAtBaseline
                stages = @($warmupStages | ForEach-Object {
                    [ordered]@{
                        stageIndex = $_.stageIndex; label = $_.label; targetRps = $_.targetRps; population = $_.population
                        startMillis = $_.startMillis; endMillis = $_.endMillis
                        start = [datetimeoffset]::FromUnixTimeMilliseconds($_.startMillis).ToString("o")
                        end = [datetimeoffset]::FromUnixTimeMilliseconds($_.endMillis).ToString("o")
                    }
                })
            }
        }
        [ordered]@{
            mode = if ($FaultRecovery) { "fault-recovery" } elseif ($NormalTimeout) { "normal-timeout" } else { "staircase" }
            stageRps = $stageRpsList
            warmupStageCount = $WarmupStageCount
            transitionRampSeconds = $RampSeconds
            stageHoldSeconds = $effectiveHoldSeconds
            steadyGuardSeconds = $SteadyGuardSeconds
            overloadThresholdRowsPerSec = $OverloadThresholdRowsPerSec
            traceAlignmentToleranceSeconds = $TraceAlignmentToleranceSeconds
            expectedPlan = $expectedPlan
            traceAnchorUtc = $events.traceAnchorUtc
            tracePlanEndUtc = $events.tracePlanEndUtc
            traceAlignment = $events.stageWindowAlignment
            traceAlignmentErrorSeconds = $events.stageWindowAlignmentErrorSeconds
            warmupEndedAt = $events.warmupEndedAt
            measurementStartedAt = $events.measurementStartedAt
            drainStartedAt = $events.drainStartedAt
            drainEndedAt = $events.drainEndedAt
            drainSeconds = $events.drainSeconds
            # Set only in normal-timeout mode. In staircase mode the warm-up is a stage of the same
            # run and is described by `stages` instead.
            warmupPhase = $warmupDocument
            segments = $segmentsDocument
            stages = $stagesDocument
        } | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDirectory "stages.json") -Encoding utf8
    }
    $events.runEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "events.json") -Encoding utf8
    Export-Latencies $events $claimSnapshot

    $submissionCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($seed.contestId)"
    $resultCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($seed.contestId)"
    $scoreboardCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($seed.contestId) AND scoreboard_applied_at IS NOT NULL"
    $uniqueCount = Get-SqlScalar "SELECT COUNT(DISTINCT id) FROM contest_submission WHERE contest_id=$($seed.contestId)"
    $completedHttpRequests = Get-GatlingSubmitRequests $gatlingStarted
    $attemptRows = Invoke-SqlRows "SELECT attempts, COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId) GROUP BY attempts ORDER BY attempts"
    @("attempts`trows") + $attemptRows | Set-Content (Join-Path $runDirectory "claim-attempts.tsv") -Encoding utf8
    $reclaimRows = Invoke-SqlRows "SELECT updated_at, submission_id, attempts FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId) AND attempts > 1 ORDER BY updated_at"
    @($reclaimRows | ForEach-Object { $p=$_ -split "`t"; [pscustomobject]@{timestamp=([datetimeoffset]::Parse($p[0]+"Z").ToString("o"));submissionId=$p[1];attempts=$p[2]} }) |
        Export-Csv (Join-Path $runDirectory "stale-reclaims.csv") -NoTypeInformation -Encoding utf8
    $duplicateEstimate = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(attempts - 1, 0)),0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId)"
    # Integrity is only decidable when all four counts came back. Missing counts are carried as null
    # and this flag, rather than being coerced to zero, so an unverified run cannot read as passed.
    $countsAvailable = -not (@($submissionCount, $uniqueCount, $resultCount, $scoreboardCount) -contains $null)
    $judgeInvocations = Get-PromMetricDelta "contest_judge_invocations_total"
    $judgeDurationSeconds = Get-PromMetricDelta "contest_judge_duration_seconds_sum"
    $claimCalls = Get-PromMetricDelta "contest_judge_claim_calls_total"
    $claimRows = Get-PromMetricDelta "contest_judge_claim_rows_total"
    $staleReclaims = Get-PromMetricDelta "contest_judge_claim_stale_total"
    $completionSuccess = Get-PromMetricDelta "contest_judge_completion_total" 'outcome="success"'
    $completionFailure = Get-PromMetricDelta "contest_judge_completion_total" 'outcome="failure"'
    $staleCompletions = Get-PromMetricDelta "contest_judge_completion_total" 'outcome="stale"'
    $storedRepublishes = Get-PromMetricDelta "contest_judge_stored_result_republish_total"
    $unavailable = New-Object System.Collections.Generic.List[string]
    if ($null -eq $judgeInvocations) { $unavailable.Add("judge invocation count: contest_judge_invocations_total was not exposed") }
    if ($faultWasInjected -and $null -ne $judgeInvocations) {
        $unavailable.Add("judge invocation, duration and claim counters are lower bounds in a SIGKILL run: the killed JVM's process-local increments after the pre-fault scrape died with it, and a lost counter is not a zero. The durable evidence is the outbox attempts column and the final row state.")
    }
    $unavailable.Add("duplicate judge time is bounded by the deterministic 50ms/2000ms profile; exact per-claim attribution is unavailable")
    if ($DispatchMode -eq "rabbit") { $unavailable.Add("Rabbit per-node running/local-waiting/reserved gauges are unavailable; worker-count x prefetch is recorded only as the configured normalized ceiling") }
    $unavailable.Add("MySQL CPU is not exposed by the stock mysql:8.0 container; connection and InnoDB lock counters are captured instead")
    if (-not $claimSnapshot.exact -and $faultWasInjected) { $unavailable.Add("killed-node claim attribution: the outbox has no claimed_by column, so killed-node-claims.csv holds every node's PUBLISHING rows at kill time; the count is a cluster-wide claimed-unfinished upper bound and is never reported as the killed node's active claims") }
    if ($faultWasInjected) { $unavailable.Add("attempts > 1 counts recovery re-claims after the lease expired, not concurrent duplicate CPU execution: the process holding the claim was SIGKILLed, so it did not keep judging. Testing true concurrent duplicate execution and fencing needs a separate docker pause -> timeout -> unpause experiment, which this round does not run.") }
    if ($null -eq $completedHttpRequests) {
        $unavailable.Add("completed HTTP submission count: Gatling simulation.log was not found")
    }
    if (-not $countsAvailable) {
        $unavailable.Add("integrity: at least one of the accepted/unique/result/scoreboard counts returned no row, so integrity is undecidable for this run and it must not be treated as passed")
    }
    $unavailable.Add("total HTTP submission attempts are unavailable because requests still in flight at Gatling maxDuration can persist after the client log closes; completedHttpRequests is reported separately")
    # A re-claim is not a duplicate execution: the process that owned the claim is gone. In a SIGKILL
    # run this identity would compare a lower-bound invocation count against a durable result count, so
    # it is reported as unavailable rather than as a small or negative number.
    $duplicateJudgements = if ($null -eq $judgeInvocations -or $null -eq $resultCount -or $faultWasInjected) { $null } else { [math]::Max(0, $judgeInvocations - $resultCount) }
    $duplicateJudgeMillisLowerBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 50 }
    $duplicateJudgeMillisUpperBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 2000 }
    $warmupVerification = $null
    if ($stagedLoad) {
        $warmupAcceptedAtEndRead = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($events.warmupContestId)"
        $warmupResults = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($events.warmupContestId)"
        $warmupDuplicateClaims = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(attempts - 1, 0)),0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.warmupContestId)"
        $warmupAttemptsHistogram = @{}
        foreach ($row in @(Invoke-SqlRows "SELECT attempts, COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.warmupContestId) GROUP BY attempts ORDER BY attempts")) {
            $p = $row -split "`t"; if ($p.Count -ge 2) { $warmupAttemptsHistogram[$p[0]] = [long]$p[1] }
        }
        $warmupVerification = [ordered]@{
            # The baseline the measured window's counter deltas are taken against is only clean if
            # no warm-up work ran after it. Both halves are recorded: the quiescence gate that was
            # required to pass, and the warm-up contest's own counts at both ends.
            contestId = $events.warmupContestId
            contestPrefix = $warmupPrefix
            quiescenceRequired = $true
            quiescent = $warmupQuiescence.quiescent
            quiescenceReason = $warmupQuiescence.reason
            quiescenceSeconds = $events.warmupQuiescenceSeconds
            quiescedAt = $events.warmupQuiescedAt
            judgeInvocationsAtQuiescence = $warmupQuiescence.judgeInvocations
            acceptedAtBaseline = $warmupAcceptedAtBaseline
            acceptedAtEnd = $warmupAcceptedAtEndRead
            acceptedGrowthAfterBaseline = if ($null -eq $warmupAcceptedAtEndRead -or $null -eq $warmupAcceptedAtBaseline) { $null } else { $warmupAcceptedAtEndRead - $warmupAcceptedAtBaseline }
            results = $warmupResults
            duplicateClaimsInWarmup = $warmupDuplicateClaims
            attemptsHistogram = $warmupAttemptsHistogram
            excludedFromMeasuredAggregates = @("latency", "accepted", "results", "scoreboard", "duplicateClaims", "judgeInvocationDelta", "throughput")
            gatlingLog = "warmup-gatling-simulation.log"
        }
    }
    # The harness records what it observed and when; the derived comparison numbers belong to the
    # analyzer, which recomputes them from timeseries.csv so they survive a re-analysis. What is kept
    # here is the evidence the analyzer cannot reconstruct: which gate each anchor came from, how long
    # each step cost, and whether the deadlines were met.
    $faultRecoveryVerification = $null
    if ($FaultRecovery) {
        $downSeconds = $null
        if ($null -ne $events.restartRequestedAt -and $null -ne $events.faultInjectedAt) {
            $downSeconds = [math]::Round(([datetimeoffset]::Parse($events.restartRequestedAt) - [datetimeoffset]::Parse($events.faultInjectedAt)).TotalSeconds, 3)
        }
        $containerSeconds = $null
        if ($null -ne $events.containerRunningAt -and $null -ne $events.restartRequestedAt) {
            $containerSeconds = [math]::Round(([datetimeoffset]::Parse($events.containerRunningAt) - [datetimeoffset]::Parse($events.restartRequestedAt)).TotalSeconds, 3)
        }
        $nodeReadySeconds = $null
        if ($null -ne $events.nodeReadyAt -and $null -ne $events.restartRequestedAt) {
            $nodeReadySeconds = [math]::Round(([datetimeoffset]::Parse($events.nodeReadyAt) - [datetimeoffset]::Parse($events.restartRequestedAt)).TotalSeconds, 3)
        }
        $readyGapSeconds = $null
        if ($null -ne $events.nodeReadyAt -and $null -ne $events.containerRunningAt) {
            $readyGapSeconds = [math]::Round(([datetimeoffset]::Parse($events.nodeReadyAt) - [datetimeoffset]::Parse($events.containerRunningAt)).TotalSeconds, 3)
        }
        $staleSeconds = $null
        if ($null -ne $events.firstStaleReclaimObservedAt -and $null -ne $events.faultInjectedAt) {
            $staleSeconds = [math]::Round(([datetimeoffset]::Parse($events.firstStaleReclaimObservedAt) - [datetimeoffset]::Parse($events.faultInjectedAt)).TotalSeconds, 3)
        }
        $triggerWaitedSeconds = $null
        if ($null -ne $recoveryPhase -and $null -ne $recoveryPhase.trigger) { $triggerWaitedSeconds = $recoveryPhase.trigger.waitedSeconds }
        $faultRecoveryVerification = [ordered]@{
            injected = if ($null -eq $recoveryPhase) { $null } else { [bool]$recoveryPhase.injected }
            trigger = if ($null -eq $recoveryPhase) { $null } else { $recoveryPhase.trigger }
            # Every anchor with the gate it came from. A null is a fact about the run, not a zero.
            anchors = [ordered]@{
                measurementStartedAt = @{ value = $events.measurementStartedAt; gate = "trace-derived start of the measured window (segment start + steadyGuardSeconds)" }
                faultScheduledAt = @{ value = $events.faultScheduledAt; gate = "measurementStartedAt + $FaultMinSteadySeconds; the instant the trigger window opened, NOT a kill deadline" }
                faultInjectedAt = @{ value = $events.faultInjectedAt; gate = "immediately after docker compose kill $KilledNode returned" }
                restartScheduledAt = @{ value = $events.restartScheduledAt; gate = "faultInjectedAt + ${DownDurationSeconds}s, computed at the injection" }
                restartRequestedAt = @{ value = $events.restartRequestedAt; gate = "recorded before docker compose start was invoked" }
                containerRunningAt = @{ value = $events.containerRunningAt; gate = "docker inspect {{.State.Running}} == true for the single container, not the nine-container health gate" }
                nodeReadyAt = @{ value = $events.nodeReadyAt; gate = "container running AND /actuator/health/readiness UP AND /actuator/prometheus scrapable AND contest_judge_claim_calls_total observed to advance" }
                firstStaleObservedAt = @{ value = $events.firstStaleReclaimObservedAt; gate = "durable SUM(attempts - 1) above its pre-fault value, polled at about 1s so the observation error is bounded rather than exact" }
                firstPostFaultResultAt = @{ value = $null; gate = "analyzer-only: MIN(result_saved_at) at or after faultInjectedAt, read from latency.csv" }
                throughputRecoveredAt = @{ value = $events.throughputRecoveredAt; gate = "5s rolling result RPS at or above 90% of the pre-fault value for 3 consecutive windows" }
                # This is the run's own during-run reading, and it is a lower bound on what the series
                # supports: the series it reads stops with this phase, so a normalisation that lands in
                # the drain is not visible here and the analyzer recomputes the instant over the drain too.
                backlogNormalizedAt = @{ value = $events.backlogNormalizedAt; gate = "judge backlog and scoreboard pending each at or below their own pre-fault p95 for 5s of wall clock with no gap inside the streak wider than ${MaxSampleGapSeconds}s; the later of the two. Read here from the fault phase's series only, so the analyzer's recomputation over the drain can be later or non-null where this is null" }
                lastReclaimedSubmissionResultAt = @{ value = $null; gate = "analyzer-only: MAX(result_saved_at) over the reclaimed cohort in latency.csv" }
                lastReclaimedSubmissionScoreboardAt = @{ value = $null; gate = "analyzer-only: MAX(scoreboard_applied_at) over the reclaimed cohort in latency.csv" }
                drainCompletedAt = @{ value = $events.drainEndedAt; gate = "the drain loop's backlog reading reached 0" }
            }
            timingErrors = [ordered]@{
                triggerWaitedSeconds = $triggerWaitedSeconds
                faultInjectionLagSeconds = $events.faultInjectionLagSeconds
                preFaultSnapshotSeconds = $events.preFaultSnapshotSeconds
                killSnapshotSeconds = if ($null -eq $recoveryPhase -or $null -eq $recoveryPhase.killSnapshot) { $null } else { $recoveryPhase.killSnapshot.seconds }
                downDurationSeconds = $downSeconds
                downDurationConfiguredSeconds = $DownDurationSeconds
                downDurationErrorSeconds = if ($null -eq $downSeconds) { $null } else { [math]::Round($downSeconds - $DownDurationSeconds, 3) }
                restartTimingErrorSeconds = $events.restartTimingErrorSeconds
                containerRunningSecondsAfterRestartRequest = $containerSeconds
                nodeReadySecondsAfterRestartRequest = $nodeReadySeconds
                nodeReadyMinusContainerRunningSeconds = $readyGapSeconds
                firstStaleSecondsAfterFault = $staleSeconds
                downWindowObservationCount = $recoveryPhase.downWindowObservationCount
                downWindowObservationBasis = "the sampler is stopped between faultInjectedAt and restartScheduledAt; only the two-count backlog observation runs, and only while more than 2s remain, so the restart deadline is never behind an observation"
            }
            # The down window is 15s because restartRequestedAt is anchored to faultInjectedAt rather
            # than to a pre-scheduled instant: a slow pre-kill snapshot moves both ends together.
            timingAssertions = [ordered]@{
                downDurationWithinHalfSecond = if ($null -eq $downSeconds) { $false } else { [math]::Abs($downSeconds - $DownDurationSeconds) -le 0.5 }
                nodeReadyAfterContainerRunning = if ($null -eq $readyGapSeconds) { $false } else { $readyGapSeconds -gt 0 }
                containerRunningAndNodeReadyDistinct = if ($null -eq $events.containerRunningAt -or $null -eq $events.nodeReadyAt) { $false } else { $events.containerRunningAt -ne $events.nodeReadyAt }
                faultInjectionLagUnder3Seconds = if ($null -eq $events.faultInjectionLagSeconds) { $false } else { $events.faultInjectionLagSeconds -le 3 }
                preFaultSnapshotUnder6Seconds = if ($null -eq $events.preFaultSnapshotSeconds) { $false } else { $events.preFaultSnapshotSeconds -le 6 }
            }
            nodeReadyGate = if ($null -eq $recoveryPhase -or $null -eq $recoveryPhase.nodeReady) { $null } else {
                [ordered]@{
                    containerRunningAt = $recoveryPhase.nodeReady.containerRunningAt.ToString("o")
                    readinessAt = $recoveryPhase.nodeReady.readinessAt.ToString("o")
                    metricsAt = $recoveryPhase.nodeReady.metricsAt.ToString("o")
                    dispatcherActiveAt = $recoveryPhase.nodeReady.dispatcherActiveAt.ToString("o")
                    containerToReadinessSeconds = $recoveryPhase.nodeReady.containerToReadinessSeconds
                    readinessToMetricsSeconds = $recoveryPhase.nodeReady.readinessToMetricsSeconds
                    metricsToDispatcherSeconds = $recoveryPhase.nodeReady.metricsToDispatcherSeconds
                    claimCallsAtFirstScrape = $recoveryPhase.nodeReady.claimCallsAtFirstScrape
                    claimCallsWhenActive = $recoveryPhase.nodeReady.claimCallsWhenActive
                    satisfied = $true
                }
            }
            nodeReadyFailure = $events.nodeReadyFailure
            recoveryTimeout = $events.recoveryTimeout
            recoveryBasis = "backlogNormalizedAt is derived by the harness from this run's own 1s samples and recomputed by the analyzer from timeseries.csv with the same p95-and-5-consecutive-sample rule"
            postRecoveryWindow = $events.postRecoveryWindow
            faultNotInjectedWithActiveWork = $events.faultNotInjectedWithActiveWork
            runValidForRecovery = (-not $events.faultNotInjectedWithActiveWork) -and ($null -ne $events.nodeReadyAt) -and (-not $events.recoveryTimeout)
            killSnapshot = if ($null -eq $recoveryPhase -or $null -eq $recoveryPhase.killSnapshot) { $null } else {
                [ordered]@{
                    file = "kill-snapshot.json"
                    unfinishedOutboxGlobal = $recoveryPhase.killSnapshot.document.atKill.unfinishedOutboxGlobal
                    unfinishedOutboxContest = $recoveryPhase.killSnapshot.document.atKill.unfinishedOutboxContest
                    scoreboardPending = $recoveryPhase.killSnapshot.document.atKill.scoreboardPending
                    clusterWideClaimedUnfinishedUpperBound = $recoveryPhase.killSnapshot.document.claims.clusterWideClaimedUnfinishedUpperBound
                    claimedUnfinishedAgeSeconds = $recoveryPhase.killSnapshot.document.claims.ageSeconds
                    basis = "the outbox has no claimed_by column, so this is a cluster-wide upper bound over every node's PUBLISHING rows at kill time"
                }
            }
            # attempts is durable after the fact, unlike claimed_at/updated_at, so the re-claim count is
            # exact while the instant it was first observed is not.
            reclaimAccounting = [ordered]@{
                reclaimedRowsAfterFault = $duplicateEstimate
                reclaimedRowsInKillSnapshot = $script:claimSnapshot.observedActiveClaimCount
                killedNodeClaimAttributionExact = [bool]$script:claimSnapshot.exact
                label = "attempts > 1 = recovery re-claims after the lease expired, NOT concurrent duplicate CPU execution"
                sigkillCounterLoss = "the killed JVM's invocation, duration and claim counters are lower bounds; the increments between the pre-fault scrape and the kill died with it, and a lost counter is not a zero"
                followUpCandidate = "docker pause -> wait past the claim timeout -> unpause, to test true concurrent duplicate execution and fencing; not run in this round"
            }
        }
    }
    $verification = [ordered]@{
        counts = @{ requests=$null; completedHttpRequests=$completedHttpRequests; accepted=$submissionCount; uniqueSubmissions=$uniqueCount; results=$resultCount; scoreboardApplied=$scoreboardCount }
        integrity = @{
            countsAvailable = $countsAvailable
            lostOrIncomplete = if ($countsAvailable) { $submissionCount - $resultCount } else { $null }
            finalResultMismatch = if ($countsAvailable) { $resultCount - $scoreboardCount } else { $null }
            passed = if ($countsAvailable) {
                $submissionCount -eq $uniqueCount -and $submissionCount -eq $resultCount -and $resultCount -eq $scoreboardCount
            } else { $false }
            reason = if ($countsAvailable) { $null } else { "one or more of the four verification counts returned no row, so this run's integrity is undecided rather than passed" }
        }
        workCost = @{ duplicateClaimEstimate=$duplicateEstimate; duplicateJudgementEstimate=$duplicateJudgements; judgeInvocations=$judgeInvocations; judgeInvocationsLowerBound=[bool]$faultWasInjected; totalJudgeMillis=if ($null -eq $judgeDurationSeconds) {$null} else {[math]::Round($judgeDurationSeconds*1000,3)}; duplicateJudgeMillisLowerBound=$duplicateJudgeMillisLowerBound; duplicateJudgeMillisUpperBound=$duplicateJudgeMillisUpperBound; claimCalls=$claimCalls; claimedRows=$claimRows; staleReclaims=$staleReclaims; completionSuccess=$completionSuccess; completionFailure=$completionFailure; staleTokenCompletions=$staleCompletions; storedResultRepublishes=$storedRepublishes; claimAttemptsFile="claim-attempts.tsv"; killedNodeClaimCount=if ($claimSnapshot.exact) {@($claimSnapshot.ids).Count} else {$null}; clusterWideClaimedUnfinishedUpperBound=$claimSnapshot.observedActiveClaimCount; claimedUnfinishedExact=[bool]$claimSnapshot.exact }
        cohortAvailability = @{ killedNodeClaimed=[bool]$claimSnapshot.exact }
        mysql = @{ statusSnapshots="metrics/*-mysql-status.tsv"; cpu=$null; lockAndConnectionCounters="captured" }
        warmup = $warmupVerification
        faultRecovery = $faultRecoveryVerification
        unavailable = @($unavailable)
    }
    $verification | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $runDirectory "db-verification.json") -Encoding utf8
    & (Join-Path $PSScriptRoot "Analyze-TradeoffRun.ps1") -RunDirectory $runDirectory
} catch {
    $events.runEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "events.json") -Encoding utf8
    $_ | Out-String | Set-Content (Join-Path $runDirectory "failure.txt") -Encoding utf8
    # A failed run keeps what it already collected so the reason can be read against the numbers,
    # and stays out of the capacity comparison either way. The preflight's own artifacts are in the
    # list because a preflight refusal is the one failure whose evidence is the ingress rather than
    # the database: preflight.json, its per-second request rows and the nginx log beside them are
    # what say whether the connection was refused below the application.
    foreach ($artifact in @("timeseries.csv", "stage-trace.csv", "warmup-stage-trace.csv", "preflight-stage-trace.csv", "preflight.json", "preflight-requests-1s.csv", "preflight-nginx.log", "requests-1s.csv", "warmup-requests-1s.csv", "capacity.csv", "backlog.csv", "kill-snapshot.json", "recovery-samples.csv", "latency.csv", "stale-reclaims.csv")) {
        $candidate = Join-Path $runDirectory $artifact
        if (Test-Path $candidate) { Write-Host "Preserved for diagnosis: $candidate" }
    }
    # The preflight's request log is copied by name prefix, not by the measured phase's, so a failure
    # thrown from inside the preflight still leaves the log its counts were read from.
    if ($null -ne $script:preflightPhaseStartedAt -and -not (Test-Path (Join-Path $runDirectory "preflight-gatling-simulation.log"))) {
        try { Copy-GatlingArtifacts -StartedAt $script:preflightPhaseStartedAt -NamePrefix "preflight-" | Out-Null } catch { Write-Warning $_ }
    }
    # A fault run dies after the kill often enough that latency.csv is still missing when the failure
    # path runs. The raw rows are in the database and the export is read-only, so reconstruct the file
    # rather than losing the one artifact the cohorts are computed from.
    if (-not (Test-Path (Join-Path $runDirectory "latency.csv"))) {
        try { Export-Latencies $events $claimSnapshot } catch { Write-Warning "latency export on the failure path failed: $_" }
    }
    if ($null -ne $gatlingStarted) {
        try { Copy-GatlingArtifacts -StartedAt $gatlingStarted | Out-Null } catch { Write-Warning $_ }
    }
    throw
} finally {
    # Before the stack goes away: nothing may still be pointed at it. A stray load generator turns
    # the teardown into a burst of connection errors that look like a fault in the system under
    # test, which is how the first normal-timeout run's failure was misread.
    $stray = $script:lastGatlingProcess
    if ($null -ne $stray) {
        try {
            if (-not $stray.HasExited) {
                Write-Warning "Stopping the Gatling process (pid $($stray.Id)) that outlived the run."
                $stray.Kill()
                $stray.WaitForExit(10000) | Out-Null
            }
        } catch { Write-Warning $_ }
    }
    if ($started -and -not $KeepStack) {
        try { Invoke-Compose -Arguments @("down") } catch { Write-Warning $_ }
    }
}

Write-Host "Experiment complete: $runDirectory"
# An explicit success exit code, so a caller inspecting $LASTEXITCODE can tell this apart from a run
# that ended without setting one. The dry-run branch has its own exit; the failure path rethrows.
exit 0
