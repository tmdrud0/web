# One run of the live-impact experiment: does a scoreboard recovery slow down, or stop, the results that
# keep arriving while it runs - and how long until the results the rollback took away come back?
#
#   $env:DB_PASSWORD = '<password>'          # never written to a file or an artifact
#   $env:DB_PORT = '3307'
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
#     -Mode full-replay -Phase calibration -TargetRps 1000
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-live-impact.ps1 `
#     -Mode full-replay -Phase run -TargetRps <calibrated> -JudgedRatePerSecond <calibrated>
#
# See docs/scoreboard-recovery-experiment/live-impact/README.md for the preconditions and PLAN.md for the
# definitions this run produces figures for.
#
# The order, which the plan fixes so the three modes are compared on one procedure:
#
#   1. guard      the rows and services this run must not touch are recorded; the judge queues are empty
#   2. seed       a contest of its own, its users and problems, and ~N judged results written to MySQL
#   3. start      the stack in the mode under test, with the batch role's experiment trace on
#   4. align      the product's own rebuild puts the seeded results on the scoreboard, and a digest of
#                 every participant's (solved, penalty) proves Redis and MySQL agree before any load
#   5. load       Gatling starts; its ramp is the warm-up
#   6. baseline   a window of normal operation, and a check that the pipeline is keeping up
#   7. snapshot   batch-1 paused; the contest's keys copied inside Redis; resumed
#   8. tail       -TailSeconds of results applied past the snapshot
#   9. rollback   batch-1 paused; processed set read; the keys put back; resumed. T_fault is the resume
#  10. observe    the tail poller counts the lost set back; the load runs on for the recovery budget and
#                 the observation that has to follow it, with no periodic oracle reads by default
#  11. finish     the load ends; the pipeline drains; one final digest
#  12. collect    trace, poller readings, judged results, Gatling log; then the summarizer
#  13. clean up   this run's rows and keys only, with the scope logged before each delete
#
# Calibration runs steps 1-6 and holds the load for -CalibrationSeconds with nothing injected, then asks
# the summarizer whether the rate was sustainable.
#
# What this never does, by rule: FLUSHALL/FLUSHDB, delete or purge a queue or stream, reset a vhost,
# DROP anything, or delete a row or key outside this run's contest and name prefix.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet("full-replay", "redis-seq", "stream-offset")][string]$Mode,
    [ValidateSet("calibration", "run")][string]$Phase = "run",
    [int]$RunIndex = 1,

    # --- the fixed conditions (PLAN.md §2) ------------------------------------------------------------
    [int]$UserCount = 10000,
    [int]$ProblemCount = 10,
    [int]$ContestDurationMinutes = 120,
    # N: judged results of the contest at T_fault. The seed tops the load up to about this many.
    [long]$TargetN = 100000,
    [double]$TargetRps = 1000,
    [long]$SubmitIntervalMillis = 10000,
    # The calibrated judged-result rate. Only used to size the seed: seed = TargetN - judged before the fault.
    [double]$JudgedRatePerSecond = 850,
    [int]$AcceptPermille = 400,
    # The warm-up: Gatling's ramp. Nothing before its end is in the measured window.
    [int]$RampSeconds = 30,
    [int]$BaselineSeconds = 30,
    # The tail depth, as time between the snapshot and the rollback.
    [int]$TailSeconds = 5,
    # How long the load keeps running after the fault for the recovery, and how much has to follow it.
    [int]$RecoveryBudgetSeconds = 300,
    [int]$ObserveAfterRecoverySeconds = 60,
    [int]$CalibrationSeconds = 60,
    [int]$DrainTimeoutSeconds = 600,
    # 0 = no periodic oracle read during the measured window (the default: the pilot's polling read
    # 40-130k MySQL rows a run and was part of what it measured). >0 reads the digest every N seconds.
    [int]$OraclePollSeconds = 0,
    [int]$TailPollIntervalMilliseconds = 100,
    # The baseline has to show the pipeline keeping up before a fault is injected; this lets a run proceed
    # when it does not, and records that it did.
    [switch]$AllowUnflatBaseline,

    [string]$ArtifactRoot = "var\scoreboard-recovery-live-impact",
    [string]$DbName = "",
    [string]$DbPort = "",
    [string]$JavaExe = "C:\Program Files\Java\jdk-17\bin\java.exe",
    [switch]$Build,
    [switch]$KeepStackRunning,
    # Leaves this run's rows and Redis keys in place for inspection. They are removed by hand afterwards
    # with the statements logged in cleanup-scope.json.
    [switch]$SkipCleanup,
    # Runs against the loadtest stack's own `mysql` service (container oj-loadtest-mysql, database
    # oj_loadtest) instead of the external oj-test-mysql instance. That container starts with its own
    # committed test root password (compose.loadtest.yaml), so nothing here ever reads, holds, or prints
    # one: Invoke-SqlScript authenticates inside the container using its own environment variable. The
    # external-DB mode (the default) is unchanged by this switch.
    [switch]$StackMySql,
    # Drops only the loadtest stack's own MySQL volume (oj-loadtest-mysql-live-impact-data) before starting it, so a
    # run can begin from an empty database. No other volume is touched. Meaningless without -StackMySql.
    [switch]$ResetMySqlVolume,
    # The one application container this runner starts on its own, before the rest of the stack, so
    # Spring Boot's own Flyway integration migrates a fresh or behind schema. Only used with -StackMySql.
    [string]$MigrationAppService = "web-1",
    # Extra compose files layered on top of the runner's own list, in order, after the -StackMySql
    # overlay. Used by the Redis-persistence report (compose.redis-persistence.yaml) instead of
    # `redis-cli CONFIG SET`, so a Redis restart during the run reloads from the compose-declared
    # command rather than losing the condition.
    [string[]]$ExtraComposeFiles = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\lib\RecoveryExperiment.ps1"
. "$PSScriptRoot\lib\RecoveryExperiment.LiveImpact.ps1"

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Object
    )

    $Object | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding utf8
}

# java.util.Properties, which is what the summarizer reads: a backslash is an escape there, so it is
# doubled, and a value is one line.
function Write-PropertiesFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Values
    )

    $builder = New-Object Text.StringBuilder
    foreach ($key in $Values.Keys) {
        $value = [string]$Values[$key]
        $value = $value.Replace('\', '\\').Replace("`r", " ").Replace("`n", " ")
        [void]$builder.Append($key).Append('=').Append($value).Append("`n")
    }
    [IO.File]::WriteAllText($Path, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))
}

function Get-NowContainerMs {
    return ConvertTo-ContainerMs -WindowsInstant ([DateTimeOffset]::UtcNow) -Clock $script:clock
}

# --- naming and configuration ---------------------------------------------------------------------

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
$stamp = Get-Date -Format "yyyyMMddHHmmss"
# Unique per invocation, not per (mode, index): the seed cleanup is scoped by this prefix, and reusing one
# would let a later run's cleanup take an earlier run's contest with it.
$runId = "li{0}_{1}{2}_{3}" -f ($Mode -replace '-', ''), $Phase.Substring(0, 1), $RunIndex, $stamp
$artifacts = Join-Path $repoRoot (Join-Path $ArtifactRoot $runId)

if (-not $StackMySql) {
    # Only the external-DB mode carries a password at all, and only through this one environment
    # variable, read once, here - never in stack-MySQL mode, where authentication happens inside the
    # database container with its own environment.
    if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
        throw "DB_PASSWORD is not set. Export it before running; it is never read from a file or written to an artifact."
    }
}
$resolvedDbName = if (-not [string]::IsNullOrWhiteSpace($DbName)) { $DbName }
elseif ($StackMySql) { "oj_loadtest" }
elseif (-not [string]::IsNullOrWhiteSpace($env:RECOVERY_PILOT_DB_NAME)) { $env:RECOVERY_PILOT_DB_NAME }
elseif (-not [string]::IsNullOrWhiteSpace($env:DB_NAME)) { $env:DB_NAME }
else { "oj_test" }
$resolvedDbPort = if (-not [string]::IsNullOrWhiteSpace($DbPort)) { $DbPort }
elseif (-not [string]::IsNullOrWhiteSpace($env:DB_PORT)) { $env:DB_PORT }
else { "3306" }
$resolvedDbContainer = if ($StackMySql) { "oj-loadtest-mysql" } else { "oj-test-mysql" }

$initializeArguments = @{
    WorktreeRoot = $repoRoot
    ArtifactDirectory = $artifacts
    RunId = $runId
    Mode = $Mode
    DbName = $resolvedDbName
    DbPort = $resolvedDbPort
    DbContainer = $resolvedDbContainer
    JavaExe = $JavaExe
    StackMySqlAuth = [bool]$StackMySql
}
if (-not $StackMySql) {
    $initializeArguments["DbPassword"] = $env:DB_PASSWORD
}
[void](Initialize-RecoveryExperiment @initializeArguments)
$config = Get-RecoveryConfig
# The live-impact overlay goes last, so the batch role's trace settings are the only thing it changes.
$config.ComposeArgs = @($config.ComposeArgs) + @("-f", "compose.live-impact.yaml")
foreach ($extraComposeFile in $ExtraComposeFiles) {
    $config.ComposeArgs = @($config.ComposeArgs) + @("-f", $extraComposeFile)
}

$concurrentUsers = [long][math]::Ceiling($TargetRps * $SubmitIntervalMillis / 1000.0)
if ($concurrentUsers -gt $UserCount) {
    throw "A rate of $TargetRps/s at a ${SubmitIntervalMillis}ms pace needs $concurrentUsers sessions but the seed creates $UserCount users."
}
if ($Phase -eq "calibration") {
    $holdSeconds = $CalibrationSeconds + 5
    $judgedBeforeFault = 0L
}
else {
    $holdSeconds = $BaselineSeconds + $TailSeconds + $RecoveryBudgetSeconds + $ObserveAfterRecoverySeconds + 10
    # The ramp is linear, so it carries half its length's worth of the full rate.
    $judgedBeforeFault = [long][math]::Round($JudgedRatePerSecond * ($RampSeconds / 2.0 + $BaselineSeconds + $TailSeconds))
}
$seedCount = [long][math]::Max(0L, $TargetN - $judgedBeforeFault)
$traceDirectory = "/tmp/sbrec-trace/$runId"
$summarizerClasses = Join-Path $repoRoot "gatling\build\classes\java\main"
$classpathFile = Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt"

$events = [ordered]@{
    runId = $runId
    mode = $Mode
    phase = $Phase
    gitHead = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    gitDirty = (@(& git -C $repoRoot status --porcelain 2>$null).Count -gt 0)
    dbName = $config.DbName
    dbPort = $config.DbPort
    userCount = $UserCount
    problemCount = $ProblemCount
    targetN = $TargetN
    targetRps = $TargetRps
    submitIntervalMillis = $SubmitIntervalMillis
    concurrentUsers = $concurrentUsers
    judgedRatePerSecondAssumed = $JudgedRatePerSecond
    judgedBeforeFaultEstimate = $judgedBeforeFault
    seedCount = $seedCount
    acceptPermille = $AcceptPermille
    rampSeconds = $RampSeconds
    baselineSeconds = $BaselineSeconds
    tailSeconds = $TailSeconds
    recoveryBudgetSeconds = $RecoveryBudgetSeconds
    requiredObservationSeconds = $ObserveAfterRecoverySeconds
    holdSeconds = $holdSeconds
    oraclePollSeconds = $OraclePollSeconds
    tailPollIntervalMs = $TailPollIntervalMilliseconds
    replayChunkSize = "500 (contest.scoreboard.recovery.full-replay.replay-batch-size default; not overridden)"
}

$script:clock = $null
$script:gatlingProcess = $null
$script:pollerStarted = $false
$script:snapshotTaken = $false
$script:seed = $null
$script:runFailed = $false
$script:outcome = "failed"
$exitCode = 1

Write-Output "live-impact $Phase run $runId (mode $Mode)"
Write-Output "  artifacts: $artifacts"
Write-Output "  load: $TargetRps/s from $concurrentUsers sessions pacing ${SubmitIntervalMillis}ms, ramp ${RampSeconds}s hold ${holdSeconds}s"
Write-Output "  seed: $seedCount judged result(s) = TargetN $TargetN - $judgedBeforeFault expected before the fault"
Write-Output ""

try {
    # --- 1. guard ----------------------------------------------------------------------------------
    if ($Build) {
        # Dockerfile copies the already-built bootJar. `docker compose --build` alone can therefore
        # produce a fresh image that still contains an old application binary, making a run appear to
        # validate code that was never executed. Keep the switch's two halves together: first produce
        # every host artifact this runner consumes, then let Compose rebuild the image below.
        Push-Location $repoRoot
        try {
            & .\gradlew.bat bootJar :gatling:classes :gatling:prepareStandaloneGatling --console=plain
            if ($LASTEXITCODE -ne 0) {
                throw "Gradle artifact build failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
    }
    foreach ($required in @($config.JavaExe, $classpathFile, (Join-Path $summarizerClasses "my\oj\perf\liveimpact\LiveImpactSummarizer.class"))) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Missing '$required'. Build first: gradlew.bat :gatling:classes :gatling:prepareStandaloneGatling (and set -JavaExe)."
        }
    }
    # redis comes up before any stack-MySQL migration: the one application container that migration
    # starts (compose.yaml's web-depends-on) will not become healthy without it. rabbitmq is not on
    # web-1's dependency list, but starting both together here rather than splitting the pair changes
    # nothing this run measures - no load has started and no fault has been injected.
    [void](Invoke-Compose -Arguments @("up", "-d", "redis", "rabbitmq"))
    Wait-ResetTargetsReady

    if ($StackMySql) {
        if ($ResetMySqlVolume) {
            Reset-StackMySqlVolume
        }
        $schema = Ensure-StackMySqlReady -WorktreeRoot $repoRoot -MigrationAppService $MigrationAppService
        $events["stackMySqlSchemaMigratedThisRun"] = $schema.Migrated
        $events["stackMySqlSchemaVersion"] = $schema.After.MaxVersion
        $events["stackMySqlSchemaTargetVersion"] = $schema.TargetVersion
        Write-Output "  stack MySQL: schema version $($schema.After.MaxVersion) (target $($schema.TargetVersion)), migrated this run=$($schema.Migrated)"
    }
    $script:sentinelBefore = Get-NonInterferenceSentinel
    $script:residualBefore = Get-ResidualRowCounts
    Write-Output "  residual rows before: $(($script:residualBefore.Keys | ForEach-Object { "$_=$($script:residualBefore[$_])" }) -join ' ')"
    [void](Assert-ExperimentDataAbsent -Phase "before this run seeds")

    $redisIdentity = Assert-RedisIsDedicated
    $queues = Get-RabbitQueueState
    Assert-OnlyProjectQueues -Queues $queues
    $live = Get-QueueCounts -Queues $queues -Name "contest.judge.live"
    $dead = Get-QueueCounts -Queues $queues -Name "contest.judge.dead"
    if ($live.Ready + $live.Unacked + $dead.Ready + $dead.Unacked -gt 0) {
        # Not drained here: that work belongs to whatever run left it, and draining it would put that run's
        # results into this one. docs/scoreboard-recovery-experiment/README.md §7 has the manual procedure.
        throw "The judge queues are not empty (live $($live.Ready)/$($live.Unacked), dead $($dead.Ready)/$($dead.Unacked)). Clear them by hand while the app tier is stopped."
    }
    $contestsBefore = Invoke-SqlInt64 -Sql "SELECT COUNT(DISTINCT contest_id) FROM contest_submission_result;" -Description "contests with results before the seed"
    $events["contestsWithResultsBeforeSeed"] = $contestsBefore
    if ($contestsBefore -gt 0) {
        Write-Output "  note: $contestsBefore other contest(s) already hold results; full-replay scans them too (recorded as N_total)"
    }
    Write-Output "  redis $($redisIdentity.container) is this project's; judge queues are empty"

    # --- 2. seed -----------------------------------------------------------------------------------
    $script:seed = New-ExperimentSeed -UserCount $UserCount -ProblemCount $ProblemCount `
        -ContestDurationMinutes $ContestDurationMinutes -EvidenceDirectory $artifacts
    Write-Output "  seeded contest $($script:seed.ContestId) with $($script:seed.UserCount) users and $($script:seed.ProblemCount) problems"
    $seeded = Add-LiveImpactSeedResults -Seed $script:seed -Count $seedCount -AcceptPermille $AcceptPermille
    Write-JsonFile -Path (Join-Path $artifacts "seed-results.json") -Object $seeded
    $events["contestId"] = $script:seed.ContestId
    $events["seedDistribution"] = "result n -> user (n mod $UserCount)+1, problem ((n div $UserCount) mod $ProblemCount)+1; $AcceptPermille permille ACCEPTED"
    Write-Output "  seeded $($seeded.Count) judged result(s) with Snowflake worker $($seeded.WorkerId)"

    # --- 3. start ----------------------------------------------------------------------------------
    $env:CONTEST_SCOREBOARD_RECOVERY_MODE = $Mode
    $env:CONTEST_SCOREBOARD_EXPERIMENT_TRACE_ENABLED = "true"
    $env:CONTEST_SCOREBOARD_EXPERIMENT_TRACE_DIRECTORY = $traceDirectory
    $upArguments = @("up", "-d")
    if ($Build) { $upArguments += "--build" }
    [void](Invoke-Compose -Arguments ($upArguments + (Get-PilotStartServices)))
    # The app tier is recreated so the batch role starts with this run's trace directory and nothing in
    # memory from an earlier run.
    [void](Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "--no-deps", "web-1", "web-2", "batch-1", "judge-1", "judge-2"))
    Wait-PilotStackHealthy
    $runtime = Assert-BatchRecoveryMode
    if ([string]$runtime.DbName -ne $config.DbName -or [string]$runtime.DbPort -ne $config.DbPort) {
        throw "The batch role is connected to '$($runtime.DbName)' on port $($runtime.DbPort); this run reads '$($config.DbName)' on $($config.DbPort)."
    }
    $artifact = Assert-BatchArtifactCarriesMode -JarPath (Join-Path $repoRoot "build\libs\web-0.0.1-SNAPSHOT.jar")
    if ($artifact.jarsMatch -eq "false" -or $artifact.modeClassPresent -eq "false") {
        throw "The batch role's jar is not this repository's build or lacks mode '$Mode' (match=$($artifact.jarsMatch), mode class=$($artifact.modeClassPresent)). Rebuild: gradlew.bat bootJar, then -Build."
    }
    $events["containerJarSha256"] = $artifact.containerJarSha256
    # A jar from before the trace existed starts without complaint and writes nothing, which would read as
    # a pipeline that applied nothing. The file is created when the trace starts, so its absence is a
    # refusal here rather than an empty series later.
    #
    # The container's own healthcheck (worker-healthcheck: "grep -aq java /proc/1/cmdline") only proves the
    # JVM process exists, not that Spring context refresh has reached the trace bean - on this machine that
    # takes ~40s (JPA/Hibernate init dominates), so Wait-PilotStackHealthy above returns healthy long before
    # the file exists. A single probe here reads as a stale jar every run. Retry it like the other
    # readiness waits in this codebase (Wait-PilotStackHealthy, Wait-PrometheusTargetsHealthy) instead.
    $traceDeadline = [DateTimeOffset]::UtcNow.AddSeconds($config.ReadyTimeoutSeconds)
    $traceOn = $false
    do {
        $traceProbe = @(Invoke-Docker -Arguments @("exec", $config.BatchContainer, "sh", "-c", "test -f $traceDirectory/live-apply.csv && echo SBRE_TRACE_ON || echo SBRE_TRACE_OFF"))
        $traceOn = $traceProbe -contains "SBRE_TRACE_ON"
        if (-not $traceOn) { Start-Sleep -Seconds 1 }
    } while (-not $traceOn -and [DateTimeOffset]::UtcNow -lt $traceDeadline)
    if (-not $traceOn) {
        throw "The batch role did not start the experiment trace in $traceDirectory within $($config.ReadyTimeoutSeconds) seconds. The jar predates it or the overlay was not applied."
    }
    Reset-EdgeRouting
    Wait-PrometheusTargetsHealthy
    [void](Assert-ExperimentSeedUsable -Phase "before the load")
    Write-Output "  stack healthy in mode $($runtime.Mode); trace on in $traceDirectory"

    # --- 4. align ----------------------------------------------------------------------------------
    [void](Invoke-LiveImpactScoreboardRebuild)
    [void](Wait-PipelineQuiescent -TimeoutSeconds $DrainTimeoutSeconds -Description "the pipeline before the load")
    $aligned = Compare-LiveImpactUserTotals
    Write-JsonFile -Path (Join-Path $artifacts "consistency-before-load.json") -Object $aligned
    if (-not $aligned.Matches) {
        throw "Redis and MySQL disagree before any load ($($aligned.ApiParticipants) vs $($aligned.OracleParticipants) participants). No figure from this run would be about a recovery."
    }
    $script:clock = Measure-LiveImpactClockOffset
    Write-JsonFile -Path (Join-Path $artifacts "clock.json") -Object $script:clock
    $events["gatlingClockOffsetMs"] = $script:clock.OffsetMs
    $events["clockOffsetUncertaintyMs"] = $script:clock.UncertaintyMs
    $events["clockMySqlMinusRedisMs"] = $script:clock.MySqlMinusRedisMs
    Write-Output "  aligned: $($aligned.ApiParticipants) participants agree; Windows->container offset $($script:clock.OffsetMs)ms (+/-$($script:clock.UncertaintyMs)ms), MySQL-Redis $($script:clock.MySqlMinusRedisMs)ms"

    $loginUser = "$($script:seed.FeederUserPrefix)_user_1"
    $loginBody = @{ userName = $loginUser; pass = $script:seed.Password } | ConvertTo-Json -Compress
    $loginResponse = Invoke-WebRequest -Uri "$($config.BaseUrl)/api/login" -Method Post -ContentType "application/json" `
        -Body $loginBody -UseBasicParsing -TimeoutSec 30
    if ([int]$loginResponse.StatusCode -ne 200) {
        throw "A login as '$loginUser' through the edge answered $([int]$loginResponse.StatusCode); the load would measure 401s."
    }

    # --- 5. load -----------------------------------------------------------------------------------
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $logbackConfig = (Resolve-Path (Join-Path $repoRoot "gatling\src\gatling\resources\logback.xml")).Path
    $resultsFolder = Join-Path $repoRoot "gatling\build\reports\gatling"
    [void](New-Item -ItemType Directory -Path $resultsFolder -Force)
    $javaArgs = @(
        "-Xms1g", "-Xmx2g",
        "-Dlogback.configurationFile=$logbackConfig",
        "-Dperf.baseUrl=$($config.BaseUrl)",
        "-Dperf.deterministic=true",
        "-Dperf.problemId.start=$($script:seed.ProblemIdStart)",
        "-Dperf.problemId.end=$($script:seed.ProblemIdEnd)",
        "-Dperf.userPrefix=$($script:seed.FeederUserPrefix)",
        "-Dperf.userIndex.start=1",
        "-Dperf.userIndex.end=$($script:seed.UserCount)",
        "-Dperf.targetRps=$TargetRps",
        "-Dperf.rampSeconds=$RampSeconds",
        "-Dperf.holdSeconds=$holdSeconds",
        "-Dperf.submitIntervalMillis=$SubmitIntervalMillis",
        # Every seeded account is in use at the target rate, so a session that dies has to be replaced by
        # an account already handed out; a queued feeder would end the whole run instead.
        "-Dperf.feeder.circular=true",
        # The run is not judged by Gatling's assertions - they are recorded, and the summarizer reads the log.
        # LoadTestAssertions requires this in (0, 100]; 0 itself throws at Gatling startup
        # ("perf.assert.minSuccessPercent must be in (0, 100]"), so this is the smallest value that is
        # still effectively no floor.
        "-Dperf.assert.minSuccessPercent=0.0001",
        "-Dperf.assert.p95Millis=600000",
        # LoadTestAssertions requires this greater than 0 as well (same reasoning as minSuccessPercent
        # above); 1 is still effectively no floor for a run sized in the tens of thousands of requests.
        "-Dperf.assert.minRequests=1",
        "-cp", $classpath,
        "io.gatling.app.Gatling",
        "-s", "my.oj.perf.ContestSubmissionSimulation",
        "-rf", $resultsFolder
    )
    foreach ($argument in $javaArgs) {
        if ($argument -match '\s') {
            throw "A Gatling argument contains whitespace and would be split by Start-Process: '$argument'. Run from a path without spaces."
        }
    }
    $gatlingDir = Join-Path $artifacts "gatling"
    [void](New-Item -ItemType Directory -Path $gatlingDir -Force)
    $loadStartedAt = [DateTimeOffset]::UtcNow
    $script:gatlingProcess = Start-Process -FilePath $config.JavaExe -ArgumentList $javaArgs -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $gatlingDir "stdout.txt") -RedirectStandardError (Join-Path $gatlingDir "stderr.txt")
    $null = $script:gatlingProcess.Handle
    $events["loadStartMs"] = ConvertTo-ContainerMs -WindowsInstant $loadStartedAt -Clock $script:clock
    Write-Output "  load started (pid $($script:gatlingProcess.Id)); warm-up is the ${RampSeconds}s ramp"

    Start-Sleep -Seconds $RampSeconds
    if ($script:gatlingProcess.HasExited) {
        throw "Gatling exited during the ramp (exit $($script:gatlingProcess.ExitCode)); see $gatlingDir."
    }
    $measureFromMs = Get-NowContainerMs
    $events["measureFromMs"] = $measureFromMs

    if ($Phase -eq "calibration") {
        # --- calibration: a steady window, nothing injected ------------------------------------------
        Start-Sleep -Seconds $CalibrationSeconds
        $events["measureToMs"] = $measureFromMs + $CalibrationSeconds * 1000L
        if (-not $script:gatlingProcess.WaitForExit(($holdSeconds + 120) * 1000)) {
            throw "Gatling did not finish within its hold plus 120s."
        }
        $events["gatlingExitCode"] = $script:gatlingProcess.ExitCode
        $events["loadEndMs"] = Get-NowContainerMs
    }
    else {
        # --- 6. baseline -------------------------------------------------------------------------------
        $baselineStart = Get-PipelineOperationalState
        Start-Sleep -Seconds $BaselineSeconds
        $baselineEnd = Get-PipelineOperationalState
        # Keeping up means the backlog in front of the scoreboard is a few seconds of inflow at most, at
        # both queues that feed it. The summarizer's before-window backlog is the precise figure; this is
        # the gate that stops a fault from being injected into a pipeline that is already falling behind.
        $allowed = [math]::Max(1000d, $JudgedRatePerSecond * 5d)
        $flat = ($baselineEnd.LiveReady -le $allowed) -and ($baselineEnd.PendingEvents -le $allowed)
        $events["baselineJudgeQueueReady"] = "$($baselineStart.LiveReady)->$($baselineEnd.LiveReady)"
        $events["baselineStreamPending"] = "$($baselineStart.PendingEvents)->$($baselineEnd.PendingEvents)"
        $events["baselineFlat"] = $flat
        Write-Output "  baseline: judge queue $($events['baselineJudgeQueueReady']), stream pending $($events['baselineStreamPending']), flat=$flat"
        if (-not $flat -and -not $AllowUnflatBaseline) {
            throw "The pipeline is not keeping up before the fault (limit $allowed). Lower -TargetRps (see calibration) or pass -AllowUnflatBaseline."
        }

        # --- 7. snapshot -------------------------------------------------------------------------------
        $memory = Assert-ShortPauseMemoryHeadroom
        $events["redisUsedMemoryBeforeSnapshot"] = $memory.usedMemory
        $countersBefore = Get-LiveImpactCounters
        $pauseStarted = [DateTimeOffset]::UtcNow
        Pause-Batch
        try {
            $snapshot = Export-ContestScoreboardShortPauseSnapshot -Label "K"
        }
        finally {
            Resume-Batch
        }
        $pauseEnded = [DateTimeOffset]::UtcNow
        $script:snapshotTaken = $true
        $events["snapshotAtMs"] = $snapshot.RedisTimeMs
        $events["snapshotPauseMs"] = [long]($pauseEnded - $pauseStarted).TotalMilliseconds
        $events["snapshotEvalMs"] = $snapshot.EvalEndMs - $snapshot.EvalStartMs
        $events["snapshotContestKeys"] = $snapshot.ContestKeys
        $events["snapshotProcessed"] = $snapshot.ProcessedCount
        $events["snapshotCheckpoint"] = $snapshot.Checkpoint
        Write-Output "  snapshot: $($snapshot.ContestKeys)+$($snapshot.GlobalKeys) key(s), processed $($snapshot.ProcessedCount), checkpoint $($snapshot.Checkpoint); batch-1 paused $($events['snapshotPauseMs'])ms (Lua $($events['snapshotEvalMs'])ms)"

        # --- 8. tail -----------------------------------------------------------------------------------
        $tailElapsed = ([DateTimeOffset]::UtcNow - $pauseEnded).TotalMilliseconds
        $tailRemaining = [int][math]::Max(0, $TailSeconds * 1000 - $tailElapsed)
        Start-Sleep -Milliseconds $tailRemaining

        # --- 9. rollback -------------------------------------------------------------------------------
        $pauseStarted = [DateTimeOffset]::UtcNow
        Pause-Batch
        try {
            $rollback = Invoke-ContestScoreboardShortPauseRollback -Label "K"
        }
        finally {
            Resume-Batch
        }
        $pauseEnded = [DateTimeOffset]::UtcNow
        $faultAtMs = [long][math]::Max((ConvertTo-ContainerMs -WindowsInstant $pauseEnded -Clock $script:clock), $rollback.RedisTimeMs)
        $events["rollbackAtMs"] = $rollback.RedisTimeMs
        $events["faultAtMs"] = $faultAtMs
        $events["faultPauseMs"] = [long]($pauseEnded - $pauseStarted).TotalMilliseconds
        $events["rollbackEvalMs"] = $rollback.EvalEndMs - $rollback.EvalStartMs
        $events["preRollbackOffset"] = $rollback.PreRollbackCheckpoint
        $events["restoredCheckpoint"] = $rollback.RestoredCheckpoint
        $events["preRollbackProcessed"] = $rollback.PreRollbackProcessedCount
        if ($rollback.PreRollbackCheckpoint -notmatch '^\d+$') {
            throw "The scoreboard held no stream checkpoint before the rollback ('$($rollback.PreRollbackCheckpoint)'); 'new' cannot be defined."
        }
        if ($rollback.RestoredCheckpoint -ne $snapshot.Checkpoint) {
            throw "The rollback restored checkpoint $($rollback.RestoredCheckpoint), not the snapshot's $($snapshot.Checkpoint)."
        }
        $lost = New-ShortPauseLostSet -Snapshot $snapshot -Rollback $rollback
        $events["lostCount"] = $lost.LostCount
        [void](Start-TailPoller -LostKey $lost.LostKey -IntervalMilliseconds $TailPollIntervalMilliseconds `
                -MaxSeconds ($RecoveryBudgetSeconds + $ObserveAfterRecoverySeconds + 300))
        $script:pollerStarted = $true
        $events["pollerStartedAtMs"] = Get-NowContainerMs
        Write-Output "  rollback: checkpoint $($rollback.PreRollbackCheckpoint) -> $($rollback.RestoredCheckpoint), $($lost.LostCount) result(s) lost; batch-1 paused $($events['faultPauseMs'])ms (Lua $($events['rollbackEvalMs'])ms)"

        # Read after the pause, never inside it.
        $counts = Get-LiveImpactResultCounts -AtContainerMs $faultAtMs
        $events["N"] = $counts.N
        $events["N_total"] = $counts.NTotalNow
        $events["contestsWithResultsAtFault"] = $counts.ContestsWithResults

        # --- 10. observe -------------------------------------------------------------------------------
        $oraclePath = Join-Path $artifacts "oracle-polls.csv"
        if ($OraclePollSeconds -gt 0) {
            "atContainerMs,matches,apiParticipants,oracleParticipants" | Set-Content -LiteralPath $oraclePath -Encoding utf8
        }
        $lastOracle = [DateTimeOffset]::UtcNow
        $lastProgress = [DateTimeOffset]::UtcNow
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds($holdSeconds + 120)
        while (-not $script:gatlingProcess.HasExited) {
            if ([DateTimeOffset]::UtcNow -gt $deadline) {
                throw "Gatling did not finish within its hold plus 120s."
            }
            Start-Sleep -Milliseconds 1000
            if (([DateTimeOffset]::UtcNow - $lastProgress).TotalSeconds -ge 10) {
                $reading = Get-TailPollerLastReading
                $sinceFault = [math]::Round(((Get-NowContainerMs) - $faultAtMs) / 1000.0)
                Write-Output "    +${sinceFault}s tail $($reading.Present)/$($reading.Total)"
                $lastProgress = [DateTimeOffset]::UtcNow
            }
            if ($OraclePollSeconds -gt 0 -and ([DateTimeOffset]::UtcNow - $lastOracle).TotalSeconds -ge $OraclePollSeconds) {
                $polled = Compare-LiveImpactUserTotals
                "$(Get-NowContainerMs),$($polled.Matches),$($polled.ApiParticipants),$($polled.OracleParticipants)" |
                    Add-Content -LiteralPath $oraclePath -Encoding utf8
                $lastOracle = [DateTimeOffset]::UtcNow
            }
        }
        $events["gatlingExitCode"] = $script:gatlingProcess.ExitCode
        $events["loadEndMs"] = Get-NowContainerMs
        $events["measureToMs"] = $events["loadEndMs"]
        $countersAfter = Get-LiveImpactCounters
        $delta = Get-LiveImpactCounterDelta -Before $countersBefore -After $countersAfter
        foreach ($key in $delta.Keys) {
            $events["counters.faultToLoadEnd.$key"] = $delta[$key]
        }
        Stop-TailPoller -DestinationPath (Join-Path $artifacts "tail-poll.csv")
        $script:pollerStarted = $false
    }
    Write-Output "  load ended (Gatling exit $($events['gatlingExitCode']))"

    # --- 11. finish --------------------------------------------------------------------------------
    $drained = $true
    try {
        [void](Wait-PipelineQuiescent -TimeoutSeconds $DrainTimeoutSeconds -Description "the pipeline after the load")
    }
    catch {
        $drained = $false
        Write-Output "  drain: $($_.Exception.Message)"
    }
    $events["drained"] = $drained
    $final = Compare-LiveImpactUserTotals
    Write-JsonFile -Path (Join-Path $artifacts "consistency-final.json") -Object $final
    $events["finalConsistent"] = $final.Matches
    Write-Output "  final digest: consistent=$($final.Matches) ($($final.ApiParticipants) vs $($final.OracleParticipants) participants)"

    # --- 12. collect -------------------------------------------------------------------------------
    Start-Sleep -Milliseconds 1500
    $traceOut = Join-Path $artifacts "trace"
    [void](New-Item -ItemType Directory -Path $traceOut -Force)
    [void](Invoke-Docker -Arguments @("cp", "$($config.BatchContainer):$traceDirectory/.", $traceOut))
    if ($Phase -eq "run") {
        $injectorOut = Join-Path $artifacts "injector"
        [void](New-Item -ItemType Directory -Path $injectorOut -Force)
        foreach ($file in @("processed-K.txt", "processed-prerollback.txt", "lost.txt")) {
            [void](Invoke-Docker -Arguments @("cp", "$($config.RedisContainer):$(Get-TailPollerDirectory)/$file", (Join-Path $injectorOut $file)))
        }
        $timeline = @(Get-BatchRecoveryTimeline -SinceUtc $loadStartedAt.UtcDateTime.ToString("o"))
        Write-JsonFile -Path (Join-Path $artifacts "recovery-log-events.json") -Object $timeline
    }
    $judgedRows = Export-LiveImpactJudged -Path (Join-Path $artifacts "judged.csv")
    $events["judgedRowsExported"] = $judgedRows
    $report = Get-ChildItem -Path $resultsFolder -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $loadStartedAt.LocalDateTime.AddSeconds(-2) } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -ne $report) {
        Copy-Item -LiteralPath $report.FullName -Destination $gatlingDir -Recurse -Force
        $events["gatlingReportDirectory"] = $report.Name
    }
    else {
        $events["gatlingReportDirectory"] = "unavailable"
    }
    Write-JsonFile -Path (Join-Path $artifacts "container-limits.json") -Object (Get-LiveImpactContainerLimits)

    $eventsFile = if ($Phase -eq "calibration") { "calibration-events.properties" } else { "run-events.properties" }
    Write-PropertiesFile -Path (Join-Path $artifacts $eventsFile) -Values $events
    $summaryOutput = @(& $config.JavaExe -cp $summarizerClasses my.oj.perf.liveimpact.LiveImpactSummarizer $artifacts --phase $Phase 2>&1)
    $summaryExit = $LASTEXITCODE
    $summaryOutput | ForEach-Object { Write-Output "  summarizer: $_" }
    if ($summaryExit -ne 0) {
        throw "The summarizer failed (exit $summaryExit)."
    }

    $complete = $drained -and $final.Matches
    if ($Phase -eq "run") {
        $summary = @{}
        Import-Csv -LiteralPath (Join-Path $artifacts "live-impact-summary.csv") | ForEach-Object { $summary[$_.metric] = $_.value }
        $complete = $complete -and ($summary["traceComplete"] -eq "true") -and ($summary["observationSufficient"] -eq "true")
    }
    $script:outcome = if ($complete) { "complete" } else { "measured-incomplete" }
    $exitCode = if ($complete) { 0 } else { 2 }
}
catch {
    $script:runFailed = $true
    Write-Output "  FAILED: $($_.Exception.Message)"
    Write-JsonFile -Path (Join-Path $artifacts "failure.json") -Object ([ordered]@{
            message = $_.Exception.Message
            position = $_.InvocationInfo.PositionMessage
            events = $events
        })
    $exitCode = 1
}
finally {
    # --- 13. clean up ------------------------------------------------------------------------------
    try { Resume-Batch } catch { Write-Output "  cleanup: resume batch-1 failed: $($_.Exception.Message)" }
    if ($null -ne $script:gatlingProcess -and -not $script:gatlingProcess.HasExited) {
        try { $script:gatlingProcess.Kill() } catch { Write-Output "  cleanup: could not stop Gatling: $($_.Exception.Message)" }
    }
    if ($script:pollerStarted) {
        try { Stop-TailPoller -DestinationPath (Join-Path $artifacts "tail-poll.csv") } catch { Write-Output "  cleanup: poller: $($_.Exception.Message)" }
    }
    try {
        foreach ($pattern in @("sbrec:snap:$($config.RunId):*", "sbrec:lost:$($config.RunId)")) {
            $removed = Remove-ShortPauseKeys -Pattern $pattern
            Write-Output "  cleanup: removed $removed redis key(s) matching $pattern"
        }
    }
    catch { Write-Output "  cleanup: redis run keys: $($_.Exception.Message)" }
    if (-not $KeepStackRunning) {
        try { [void](Invoke-Compose -Arguments @("stop", "web-1", "web-2", "batch-1", "judge-1", "judge-2")) }
        catch { Write-Output "  cleanup: stopping the app tier failed: $($_.Exception.Message)" }
    }
    if ($null -ne $script:seed -and -not $SkipCleanup) {
        try {
            # The scope is written and printed before anything is deleted.
            $scope = [ordered]@{
                contestId = $config.ContestId
                seedPrefix = $config.SeedPrefix
                redisPattern = "$($config.ScoreboardKeyPrefix)$($config.ContestId):*"
                counts = Get-ExperimentScopeCounts
                statements = @(Get-ExperimentTableScope | ForEach-Object { "DELETE FROM $($_.Table) WHERE $($_.Where)" })
            }
            Write-JsonFile -Path (Join-Path $artifacts "cleanup-scope.json") -Object $scope
            Write-Output "  cleanup: contest $($scope.contestId), prefix '$($scope.seedPrefix)'"
            foreach ($key in $scope.counts.Keys) { Write-Output "    $key : $($scope.counts[$key]) row(s)" }
            # Redis first: the MySQL cleanup clears the contest scope, and the key delete refuses to run
            # without one.
            $removedKeys = Remove-ShortPauseKeys -Pattern $scope.redisPattern
            [void](Remove-ExperimentData -EvidenceDirectory $artifacts)
            Write-Output "  cleanup: removed this run's rows and $removedKeys scoreboard key(s)"
        }
        catch { Write-Output "  cleanup: $($_.Exception.Message)" }
    }
    elseif ($SkipCleanup) {
        Write-Output "  cleanup skipped (-SkipCleanup): contest $($config.ContestId), prefix '$($config.SeedPrefix)'"
    }
    try {
        if ($null -ne (Get-Variable -Name sentinelBefore -Scope Script -ErrorAction SilentlyContinue)) {
            Assert-NonInterferenceIntact -Before $script:sentinelBefore -Phase "after run $runId" `
                -EvidencePath (Join-Path $artifacts "non-interference-after.json")
        }
    }
    catch { Write-Output "  non-interference: $($_.Exception.Message)"; $exitCode = 1 }
    Write-Output "  outcome: $($script:outcome) (exit $exitCode)"
}
exit $exitCode
