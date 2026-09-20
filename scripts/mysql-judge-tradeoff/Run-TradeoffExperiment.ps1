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
    [int]$MeasurementSeconds = 60
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

# The two staged experiments share the machinery - a traced hold analysed per window, one stack for
# the whole run - but not their parameters: the staircase sweeps a ladder of rates in one run, while
# the normal-timeout run holds a single rate so that the claim timeout is the only thing that changes
# between runs. They are kept apart rather than merged into one mode because the staircase's ladder
# is what defines its stage labels.
$stagedLoad = [bool]$Staircase -or [bool]$NormalTimeout
if ($Staircase -and $NormalTimeout) { throw "-Staircase and -NormalTimeout are different experiments; pass one of them." }
$warmupPrefix = ""
$measurementPrefix = ""
$measurementHoldSeconds = 0
if ($NormalTimeout) {
    if ($DispatchMode -ne "mysql") { throw "-NormalTimeout measures MySQL claim dispatch, so -DispatchMode must be mysql." }
    if ($FaultEnabled) { throw "-NormalTimeout measures the fault-free steady state and does not inject faults." }
    if (-not $PSBoundParameters.ContainsKey("TargetRps")) { throw "-NormalTimeout requires an explicit -TargetRps: the offered rate is an input to the comparison across timeouts, not a default." }
    if ($WarmupSeconds -lt 12) { throw "-WarmupSeconds must be at least 12, or the warm-up phase is not long enough to reach a steady state." }
    if ($MeasurementSeconds -lt 12) { throw "-MeasurementSeconds must be at least 12 so a measured window has enough samples to classify." }
    if ($SteadyGuardSeconds -lt 0) { throw "-SteadyGuardSeconds must not be negative." }
    if ($UserCount -lt 1000) { throw "Normal-timeout runs require -UserCount of at least 1000." }
    if ($DrainTimeoutSeconds -lt 300) { throw "Normal-timeout runs require -DrainTimeoutSeconds of at least 300." }
    # Each phase is its own Gatling invocation with one stage, so warmupStageCount is 0: the phase
    # boundary is the harness draining the pipeline between the two runs, not a warm-up stage inside
    # one schedule. The hold carries the steady guard on top of the measured window, so the window
    # the percentiles are read over is exactly -MeasurementSeconds long.
    $stageRpsList = @([double]$TargetRps)
    $WarmupStageCount = 0
    $measurementHoldSeconds = $MeasurementSeconds + $SteadyGuardSeconds
    # Both contests are seeded from the seed alone, so a rerun of the same timeout reseeds the same
    # users and therefore the same code strings: the 5% slow-job split is keyed on the code, and an
    # identical code set is what makes the synthetic judge work reproducible across the six runs.
    $warmupPrefix = "norm_warm_$LatencySeed"
    $measurementPrefix = "norm_meas_$LatencySeed"
}
$effectiveHoldSeconds = if ($NormalTimeout) { $measurementHoldSeconds } else { $StageHoldSeconds }
$workloadPrefix = if ($NormalTimeout) { $measurementPrefix } else { "tradeoff_seed_$LatencySeed" }

# The staircase drives the stack into overload on purpose, where the API rate limiter refuses
# requests the judge never saw. Those refusals are part of the measurement, not a fault, so the
# staircase default is looser than the fault experiments' 95%; an explicit value still wins, and
# Gatling exiting 2 over it is recorded rather than treated as a failed run.
$assertMinSuccess = if ($Staircase) {
    if ($PSBoundParameters.ContainsKey("AssertMinSuccessPercent")) { $AssertMinSuccessPercent } else { 80 }
} elseif ($NormalTimeout) {
    # A normal-timeout run is offered about 70% of the measured knee, so it stays well inside the
    # rate limiter and the staircase's looser bound is not needed; an explicit value still wins.
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
} elseif ($NormalTimeout) {
    $population = [int][math]::Max(1, [math]::Ceiling($TargetRps * 3100 / 1000))
    $expectedPlan = [ordered]@{
        targetRps = $TargetRps
        population = $population
        warmupPhase = [ordered]@{
            contestPrefix = $warmupPrefix; rampSeconds = $RampSeconds
            holdSeconds = $WarmupSeconds; seconds = $RampSeconds + $WarmupSeconds
        }
        measurementPhase = [ordered]@{
            contestPrefix = $measurementPrefix; rampSeconds = $RampSeconds
            holdSeconds = $measurementHoldSeconds; steadyGuardSeconds = $SteadyGuardSeconds
            measuredWindowSeconds = $MeasurementSeconds; seconds = $RampSeconds + $measurementHoldSeconds
        }
        totalSeconds = 2 * $RampSeconds + $WarmupSeconds + $measurementHoldSeconds
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
    faultEnabled = [bool]$FaultEnabled; faultAtSeconds = $FaultAtSeconds
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
        [switch]$ObserveRecovery
    )
    $port = if ($Node -eq "judge-1") { 19001 } else { 19002 }
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
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
            (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$($entry[1])/actuator/prometheus").Content |
                Set-Content $path -Encoding utf8
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

function Save-CapacitySample {
    param([Parameter(Mandatory = $true)][string]$Phase)
    $path = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $path)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $path -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", 19001), @("judge-2", 19002))) {
        $values = Get-JudgeGauges -Port $entry[1]
        "$([datetimeoffset]::UtcNow.ToString('o')),$Phase,$($entry[0]),$($values.running),$($values.queued),$($values.reserved)" |
            Add-Content $path -Encoding utf8
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
    "$( [datetimeoffset]::UtcNow.ToString('o')),$Phase,$pendingText,$unappliedText" | Add-Content $path -Encoding utf8
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
        "-Dperf.stageRps=$TargetRps", "-Dperf.warmupStageCount=0"
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
        [string]$Purpose = "this contest"
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
        foreach ($port in @(19001, 19002)) {
            $state = Get-JudgeLiveState -Port $port
            if ($null -eq $state.reserved -or $null -eq $state.invocations) { $readable = $false } else {
                $reserved += $state.reserved
                $invocations += $state.invocations
            }
        }
        $quiet = $false
        if ($readable -and $null -ne $unfinished -and $null -ne $accepted -and $null -ne $results -and $null -ne $applied) {
            $reason = "outbox unfinished $unfinished, judge reserved $reserved, accepted $accepted, results $results, scoreboard applied $applied, judge invocations $invocations"
            $quiet = ($unfinished -eq 0 -and $reserved -eq 0 -and $results -eq $accepted -and $applied -eq $accepted -and
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
    $row | Add-Content $path -Encoding utf8

    # capacity.csv keeps the run-level executor aggregate the analyzer already reports.
    $capacityPath = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $capacityPath)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $capacityPath -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", $judge1), @("judge-2", $judge2))) {
        "$($tickStart.ToString('o')),$Phase,$($entry[0]),$($entry[1].running),$($entry[1].queued),$($entry[1].reserved)" |
            Add-Content $capacityPath -Encoding utf8
    }

    return [pscustomobject]@{
        unfinishedContest = ConvertTo-Int64OrNull $values.unfinishedContest
        unfinishedGlobal = ConvertTo-Int64OrNull $values.unfinishedGlobal
        unappliedContest = ConvertTo-Int64OrNull $values.unappliedContest
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
        if ($FaultEnabled -and $node -eq $KilledNode) {
            $beforeKill = Get-PromMetricSum "pre-fault" $Metric $RequiredTag $node
            if ($null -eq $beforeKill) { $beforeKill = 0.0 }
            # The killed JVM contributes start..pre-fault. Its replacement JVM
            # starts counters at zero, so its complete end value is the recovery
            # contribution; subtracting a post-restart scrape would drop work.
            $total += [math]::Max(0, $beforeKill - $start) + [math]::Max(0, $end)
        } else {
            $total += [math]::Max(0, $end - $start)
        }
    }
    return $total
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
            Write-Host "Normal timeout: warm-up at $TargetRps RPS for ${WarmupSeconds}s in '$warmupPrefix', full drain, then measurement at $TargetRps RPS for ${MeasurementSeconds}s in '$measurementPrefix' (hold ${effectiveHoldSeconds}s = measurement + ${SteadyGuardSeconds}s guard), claim timeout $MySqlClaimTimeout, total $($expectedPlan.totalSeconds)s, population $($expectedPlan.population)."
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
$staircaseTrace = $null; $staircaseStages = @(); $capturedBoundaries = @{}
$warmupSeed = $null; $warmupTrace = $null; $warmupStages = @(); $warmupQuiescence = $null
$warmupAcceptedAtBaseline = $null
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
    if ($NormalTimeout) {
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
    } else {
        $workloadPrefix = "tradeoff_seed_$LatencySeed"
        $seedRequest = @{ prefix=$workloadPrefix; userCount=$UserCount; problemCount=5; durationMinutes=60; reset=$true } | ConvertTo-Json -Compress
        $seed = Invoke-RestMethod -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" -Body $seedRequest -TimeoutSec 60
    }
    $events.contestId = [long]$seed.contestId
    $contestId = [long]$seed.contestId
    # In a normal-timeout run this first snapshot is the warm-up phase's starting point; the baseline
    # the measured window is read against is taken again once the warm-up has drained.
    Save-MetricsSnapshot $(if ($NormalTimeout) { "warmup-start" } else { "start" })

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

    if ($NormalTimeout) {
        # Warm-up phase. It is offered the same rate and the same workload as the measurement and
        # differs only in which contest it writes to, so the measurement starts against a stack that
        # has already been through the same code paths (JIT, connection pool, buffer pool). Its
        # results are never part of the measured aggregates: the measured contest is a different
        # contest, so nothing it does can be counted by any query scoped to the measurement.
        $warmupTracePath = (Join-Path $runDirectory "warmup-stage-trace.csv") -replace '\\', '/'
        $events.warmupPhaseStartedAt = [datetimeoffset]::UtcNow.ToString("o")
        $warmupPhase = Start-GatlingLoadPhase -PhaseName "warmup" -Seed $warmupSeed -UserPrefix $warmupPrefix `
            -HoldSeconds $WarmupSeconds -TracePath $warmupTracePath
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
        # The measured window's counters are taken as a delta from the scrape below, so the warm-up
        # must be fully finished - judged, applied and no worker still inside a judge call - before
        # it is taken. Otherwise warm-up work is charged to the measured phase.
        $warmupQuiescence = Wait-PipelineQuiescent -ContestId $events.warmupContestId -TimeoutSeconds 300 -Purpose "the warm-up contest"
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
            if ($null -eq $drainSample.unfinishedContest -or $null -eq $drainSample.unappliedContest) {
                $backlog = $null
            } else {
                $backlog = $drainSample.unfinishedContest + $drainSample.unappliedContest
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
    if ($NormalTimeout) {
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
        if ($NormalTimeout) {
            $warmupDocument = [ordered]@{
                contestId = $events.warmupContestId
                contestPrefix = $warmupPrefix
                userPrefix = $warmupPrefix
                targetRps = $TargetRps
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
            mode = if ($NormalTimeout) { "normal-timeout" } else { "staircase" }
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
    if ($FaultEnabled -and $null -ne $judgeInvocations) {
        $unavailable.Add("judge invocation and completion counters are lower bounds in SIGKILL runs because increments after the pre-fault scrape can be lost with the killed JVM")
    }
    $unavailable.Add("duplicate judge time is bounded by the deterministic 50ms/2000ms profile; exact per-claim attribution is unavailable")
    if ($DispatchMode -eq "rabbit") { $unavailable.Add("Rabbit per-node running/local-waiting/reserved gauges are unavailable; worker-count x prefetch is recorded only as the configured normalized ceiling") }
    $unavailable.Add("MySQL CPU is not exposed by the stock mysql:8.0 container; connection and InnoDB lock counters are captured instead")
    if (-not $claimSnapshot.exact -and $FaultEnabled) { $unavailable.Add("killed-node claim attribution: schema has no claimed_by column; killed-node-claims.csv contains all active claims at kill time") }
    if ($null -eq $completedHttpRequests) {
        $unavailable.Add("completed HTTP submission count: Gatling simulation.log was not found")
    }
    if (-not $countsAvailable) {
        $unavailable.Add("integrity: at least one of the accepted/unique/result/scoreboard counts returned no row, so integrity is undecidable for this run and it must not be treated as passed")
    }
    $unavailable.Add("total HTTP submission attempts are unavailable because requests still in flight at Gatling maxDuration can persist after the client log closes; completedHttpRequests is reported separately")
    $duplicateJudgements = if ($null -eq $judgeInvocations -or $null -eq $resultCount -or $FaultEnabled) { $null } else { [math]::Max(0, $judgeInvocations - $resultCount) }
    $duplicateJudgeMillisLowerBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 50 }
    $duplicateJudgeMillisUpperBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 2000 }
    $warmupVerification = $null
    if ($NormalTimeout) {
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
        workCost = @{ duplicateClaimEstimate=$duplicateEstimate; duplicateJudgementEstimate=$duplicateJudgements; judgeInvocations=$judgeInvocations; judgeInvocationsLowerBound=[bool]$FaultEnabled; totalJudgeMillis=if ($null -eq $judgeDurationSeconds) {$null} else {[math]::Round($judgeDurationSeconds*1000,3)}; duplicateJudgeMillisLowerBound=$duplicateJudgeMillisLowerBound; duplicateJudgeMillisUpperBound=$duplicateJudgeMillisUpperBound; claimCalls=$claimCalls; claimedRows=$claimRows; staleReclaims=$staleReclaims; completionSuccess=$completionSuccess; completionFailure=$completionFailure; staleTokenCompletions=$staleCompletions; storedResultRepublishes=$storedRepublishes; claimAttemptsFile="claim-attempts.tsv"; killedNodeClaimCount=if ($claimSnapshot.exact) {@($claimSnapshot.ids).Count} else {$null}; allActiveClaimsAtKill=$claimSnapshot.observedActiveClaimCount }
        cohortAvailability = @{ killedNodeClaimed=[bool]$claimSnapshot.exact }
        mysql = @{ statusSnapshots="metrics/*-mysql-status.tsv"; cpu=$null; lockAndConnectionCounters="captured" }
        warmup = $warmupVerification
        unavailable = @($unavailable)
    }
    $verification | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $runDirectory "db-verification.json") -Encoding utf8
    & (Join-Path $PSScriptRoot "Analyze-TradeoffRun.ps1") -RunDirectory $runDirectory
} catch {
    $events.runEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "events.json") -Encoding utf8
    $_ | Out-String | Set-Content (Join-Path $runDirectory "failure.txt") -Encoding utf8
    # A failed run keeps what it already collected so the reason can be read against the numbers,
    # and stays out of the capacity comparison either way.
    foreach ($artifact in @("timeseries.csv", "stage-trace.csv", "warmup-stage-trace.csv", "capacity.csv", "backlog.csv")) {
        $candidate = Join-Path $runDirectory $artifact
        if (Test-Path $candidate) { Write-Host "Preserved for diagnosis: $candidate" }
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
