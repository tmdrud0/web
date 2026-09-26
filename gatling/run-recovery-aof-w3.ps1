# Report 3 (AOF cost), workload W3: what a real `redis-server` process kill loses under one Redis
# persistence condition (no / everysec / always), and whether the app's own stream-offset recovery
# fills the gap back in afterwards.
#
# One call = one run. Seeds a baseline directly into MySQL (like W1/W2), rebuilds the board, starts a
# real Gatling submission load, lets it run into a steady window, then:
#   1. docker kill -s KILL <redis>            (a real crash, not `compose stop`)
#   2. docker pause <batch>                    (freeze the consumer's process and filesystem)
#   3. docker compose up -d redis              (restart in place; same volume, so no data is invented -
#                                                whatever the persistence condition actually kept is
#                                                what comes back)
#   4. read the restarted Redis's own INFO/log for whether it loaded from AOF or RDB
#   5. SMEMBERS the experiment contest's processed-submission set (Get-RedisSetMembers)
#   6. docker unpause <batch>
# Loss = submission ids the trace (trace/live-apply.csv, written only for a *stream-consumed* apply that
# fully succeeded - see ContestScoreboardStreamProcessor.applyBatch) recorded as applied at or before the
# kill instant, minus the ids the processed set actually held right after the restart, before the
# consumer had a chance to run again. What happens *after* unpause (the app's own recovery replaying the
# stream from Redis's restored checkpoint) is a separate question, checked by a final digest once Gatling
# finishes and the pipeline drains.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-aof-w3.ps1 -Condition no

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet("no", "everysec", "always")][string]$Condition,

    [long]$TargetN = 100000,
    [int]$UserCount = 10000,
    [int]$ProblemCount = 10,
    [int]$ContestDurationMinutes = 120,
    [int]$AcceptPermille = 400,
    [double]$TargetRps = 500,
    [long]$SubmitIntervalMillis = 5000,
    [double]$JudgedRatePerSecond = 457.733,
    [int]$RampSeconds = 30,
    [int]$KillAfterSeconds = 60,
    [int]$ObserveAfterSeconds = 240,
    [int]$DrainTimeoutSeconds = 1800,

    [string]$ArtifactRoot = "var\scoreboard-recovery-aof-w3",
    [string]$JavaExe = "C:\Program Files\Java\jdk-17\bin\java.exe",
    [string]$MigrationAppService = "web-1"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\lib\RecoveryExperiment.ps1"
. "$PSScriptRoot\lib\RecoveryExperiment.LiveImpact.ps1"

function Write-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)]$Object)
    $Object | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Get-NowContainerMs {
    return ConvertTo-ContainerMs -WindowsInstant ([DateTimeOffset]::UtcNow) -Clock $script:clock
}

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$runId = "aofw3{0}_{1}" -f $Condition, $stamp
$artifacts = Join-Path $repoRoot (Join-Path $ArtifactRoot $runId)
$traceDirectory = "/tmp/sbrec-trace/$runId"

[void](Initialize-RecoveryExperiment `
        -WorktreeRoot $repoRoot -ArtifactDirectory $artifacts -RunId $runId `
        -Mode "stream-offset" -ProjectName "oj-loadtest" `
        -DbContainer "oj-loadtest-mysql" -DbName "oj_loadtest" -StackMySqlAuth `
        -JavaExe $JavaExe)
$config = Get-RecoveryConfig
$config.ComposeArgs = @($config.ComposeArgs) + @("-f", "compose.redis-persistence.yaml")

$env:REDIS_APPENDONLY = if ($Condition -eq "no") { "no" } else { "yes" }
$env:REDIS_APPENDFSYNC = if ($Condition -eq "always") { "always" } else { "everysec" }
$env:CONTEST_SCOREBOARD_RECOVERY_MODE = "stream-offset"
$env:CONTEST_SCOREBOARD_EXPERIMENT_TRACE_ENABLED = "true"
$env:CONTEST_SCOREBOARD_EXPERIMENT_TRACE_DIRECTORY = $traceDirectory

$concurrentUsers = [long][math]::Ceiling($TargetRps * $SubmitIntervalMillis / 1000.0)
if ($concurrentUsers -gt $UserCount) {
    throw "A rate of $TargetRps/s at a ${SubmitIntervalMillis}ms pace needs $concurrentUsers sessions but the seed creates $UserCount users."
}
$judgedBeforeKill = [long][math]::Round($JudgedRatePerSecond * ($RampSeconds / 2.0 + $KillAfterSeconds))
$seedCount = [long][math]::Max(0L, $TargetN - $judgedBeforeKill)
$holdSeconds = $KillAfterSeconds + $ObserveAfterSeconds + 10

$events = [ordered]@{
    runId = $runId
    condition = $Condition
    gitHead = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    gitDirty = (@(& git -C $repoRoot status --porcelain 2>$null).Count -gt 0)
    targetN = $TargetN
    seedCount = $seedCount
    redisAppendonly = $env:REDIS_APPENDONLY
    redisAppendfsync = $env:REDIS_APPENDFSYNC
    targetRps = $TargetRps
    killAfterSeconds = $KillAfterSeconds
    observeAfterSeconds = $ObserveAfterSeconds
}

$script:seed = $null
$script:gatlingProcess = $null
$script:batchPausedHere = $false
$script:outcome = "failed"
$exitCode = 1

Write-Output "aof-w3 condition=$Condition ($runId)"
Write-Output "  artifacts: $artifacts"

try {
    # --- stack up (never -ResetMySqlVolume: see the everysec contamination incident in W2) -----------
    [void](Invoke-Compose -Arguments @("up", "-d", "rabbitmq"))
    [void](Invoke-Compose -Arguments @("up", "-d", "redis"))
    Wait-ResetTargetsReady
    [void](Ensure-StackMySqlReady -WorktreeRoot $repoRoot -MigrationAppService $MigrationAppService)
    [void](Invoke-Compose -Arguments (@("up", "-d") + (Get-PilotStartServices)))
    # Always force-recreate: this run's trace directory and recovery-mode env must be the ones the new
    # process starts with, not whatever an earlier run (or condition) left running.
    [void](Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "--no-deps", "web-1", "web-2", "batch-1", "judge-1", "judge-2"))
    Wait-PilotStackHealthy
    Reset-EdgeRouting

    $redisIdentity = Assert-RedisIsDedicated
    Write-Output "  redis $($redisIdentity.container) is this project's"
    [void](Assert-ExperimentDataAbsent -Phase "before this run seeds")

    $persistBefore = Get-RedisInfoSection -Section "persistence" -Description "persistence state before the run"
    $expectAof = if ($Condition -eq "no") { "0" } else { "1" }
    if ([string]$persistBefore["aof_enabled"] -ne $expectAof) {
        throw "Redis reports aof_enabled=$($persistBefore['aof_enabled']) for condition '$Condition' (expected $expectAof)."
    }
    $events["aofEnabledConfiguredBefore"] = $persistBefore["aof_enabled"]

    # Trace file existence gate (same pitfall the live-impact runner documents: the worker healthcheck
    # is satisfied long before Spring context refresh reaches the trace bean).
    $traceDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    $traceOn = $false
    do {
        $traceProbe = @(Invoke-Docker -Arguments @("exec", $config.BatchContainer, "sh", "-c", "test -f $traceDirectory/live-apply.csv && echo SBRE_TRACE_ON || echo SBRE_TRACE_OFF"))
        $traceOn = $traceProbe -contains "SBRE_TRACE_ON"
        if (-not $traceOn) { Start-Sleep -Seconds 1 }
    } while (-not $traceOn -and [DateTimeOffset]::UtcNow -lt $traceDeadline)
    if (-not $traceOn) {
        throw "The batch role did not start the experiment trace in $traceDirectory within 120 seconds."
    }

    # --- seed + rebuild + align --------------------------------------------------------------------
    $script:seed = New-ExperimentSeed -UserCount $UserCount -ProblemCount $ProblemCount `
        -ContestDurationMinutes $ContestDurationMinutes -EvidenceDirectory $artifacts
    Write-Output "  seeded contest $($script:seed.ContestId) with $($script:seed.UserCount) users, $($script:seed.ProblemCount) problems"
    $seeded = Add-LiveImpactSeedResults -Seed $script:seed -Count $seedCount -AcceptPermille $AcceptPermille
    $events["contestId"] = $script:seed.ContestId
    $events["seededResults"] = $seeded.ResultRows
    Write-Output "  seeded $($seeded.ResultRows) judged result(s); rebuilding the board"

    $readyDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    $rebuildOutput = $null
    while ($null -eq $rebuildOutput) {
        try { $rebuildOutput = Invoke-LiveImpactScoreboardRebuild }
        catch {
            if ([DateTimeOffset]::UtcNow -gt $readyDeadline) { throw "The rebuild endpoint did not answer within 120s: $($_.Exception.Message)" }
            Start-Sleep -Seconds 3
        }
    }
    [void](Wait-PipelineQuiescent -TimeoutSeconds $DrainTimeoutSeconds -Description "the pipeline before the load")
    $aligned = Compare-LiveImpactUserTotals
    Write-JsonFile -Path (Join-Path $artifacts "consistency-before-load.json") -Object $aligned
    if (-not $aligned.Matches) {
        throw "Redis and MySQL disagree before any load ($($aligned.ApiParticipants) vs $($aligned.OracleParticipants))."
    }
    $script:clock = Measure-LiveImpactClockOffset
    Write-JsonFile -Path (Join-Path $artifacts "clock.json") -Object $script:clock
    Write-Output "  aligned: $($aligned.ApiParticipants) participants agree"

    $loginUser = "$($script:seed.FeederUserPrefix)_user_1"
    $loginBody = @{ userName = $loginUser; pass = $script:seed.Password } | ConvertTo-Json -Compress
    $loginResponse = Invoke-WebRequest -Uri "$($config.BaseUrl)/api/login" -Method Post -ContentType "application/json" `
        -Body $loginBody -UseBasicParsing -TimeoutSec 30
    if ([int]$loginResponse.StatusCode -ne 200) {
        throw "A login as '$loginUser' through the edge answered $([int]$loginResponse.StatusCode)."
    }

    # --- load ---------------------------------------------------------------------------------------
    $classpathFile = Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt"
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
        "-Dperf.feeder.circular=true",
        "-Dperf.assert.minSuccessPercent=0.0001",
        "-Dperf.assert.p95Millis=600000",
        "-Dperf.assert.minRequests=1",
        "-cp", $classpath,
        "io.gatling.app.Gatling",
        "-s", "my.oj.perf.ContestSubmissionSimulation",
        "-rf", $resultsFolder
    )
    $gatlingDir = Join-Path $artifacts "gatling"
    [void](New-Item -ItemType Directory -Path $gatlingDir -Force)
    $loadStartedAt = [DateTimeOffset]::UtcNow
    $script:gatlingProcess = Start-Process -FilePath $config.JavaExe -ArgumentList $javaArgs -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $gatlingDir "stdout.txt") -RedirectStandardError (Join-Path $gatlingDir "stderr.txt")
    $null = $script:gatlingProcess.Handle
    Write-Output "  load started (pid $($script:gatlingProcess.Id)); ramp ${RampSeconds}s, kill at +${KillAfterSeconds}s of steady state, hold ${holdSeconds}s"

    Start-Sleep -Seconds $RampSeconds
    if ($script:gatlingProcess.HasExited) {
        throw "Gatling exited during the ramp (exit $($script:gatlingProcess.ExitCode))."
    }
    Start-Sleep -Seconds $KillAfterSeconds
    if ($script:gatlingProcess.HasExited) {
        throw "Gatling exited before the kill was injected (exit $($script:gatlingProcess.ExitCode))."
    }

    # --- crash --------------------------------------------------------------------------------------
    $backlogAtKill = Get-PipelineOperationalState
    $events["backlogAtKillPendingEvents"] = $backlogAtKill.PendingEvents
    $events["backlogAtKillScoreboardUnapplied"] = $backlogAtKill.ScoreboardUnapplied
    $killAtContainerMs = Get-NowContainerMs
    $events["killAtContainerMs"] = $killAtContainerMs
    Write-Output "  backlog at kill: pendingEvents=$($backlogAtKill.PendingEvents) unapplied=$($backlogAtKill.ScoreboardUnapplied)"

    [void](Invoke-Docker -Arguments @("kill", "-s", "KILL", $config.RedisContainer))
    $killedAt = [DateTimeOffset]::UtcNow
    Write-Output "  killed $($config.RedisContainer) (SIGKILL) at $($killedAt.ToString('o'))"
    Pause-Batch
    $script:batchPausedHere = $true

    [void](Invoke-Compose -Arguments @("up", "-d", "redis"))
    $redisReadyDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    $redisUp = $false
    while (-not $redisUp -and [DateTimeOffset]::UtcNow -lt $redisReadyDeadline) {
        try {
            $reply = @(Invoke-RedisText -RedisArguments @("PING"))
            $redisUp = ($reply -match "PONG")
        }
        catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $redisUp) { throw "Redis did not answer PING within 120s of restarting." }
    $restartedAt = [DateTimeOffset]::UtcNow
    $events["redisDownForSeconds"] = [math]::Round(($restartedAt - $killedAt).TotalSeconds, 2)

    $loadLog = @(Invoke-Docker -Arguments @("logs", "--since", $killedAt.UtcDateTime.ToString("o"), $config.RedisContainer)) -join "`n"
    Write-JsonFile -Path (Join-Path $artifacts "redis-restart-log.json") -Object @($loadLog -split "`n")
    $loadedFromAof = $loadLog -match "DB loaded from append only file|Reading RDB base file|Reading the remaining AOF"
    $loadedFromRdb = $loadLog -match "DB loaded from disk"
    $events["restartLoadedFromAof"] = [bool]$loadedFromAof
    $events["restartLoadedFromRdb"] = [bool]$loadedFromRdb
    Write-Output "  redis restarted: loadedFromAof=$loadedFromAof loadedFromRdb=$loadedFromRdb"

    $persistAfterRestart = Get-RedisInfoSection -Section "persistence" -Description "persistence state after restart"
    foreach ($key in @("aof_enabled", "rdb_last_load_keys_loaded", "aof_last_bgrewrite_status")) {
        if ($persistAfterRestart.Contains($key)) { $events["persist.afterRestart.$key"] = $persistAfterRestart[$key] }
    }

    $processedAfterRestart = @(Get-RedisSetMembers -Key $config.ProcessedKey)
    $events["processedCountAfterRestart"] = $processedAfterRestart.Count
    Write-Output "  processed set after restart: $($processedAfterRestart.Count) submission id(s)"

    $traceOutBeforeUnpause = Join-Path $artifacts "trace-at-crash"
    [void](New-Item -ItemType Directory -Path $traceOutBeforeUnpause -Force)
    [void](Invoke-Docker -Arguments @("cp", "$($config.BatchContainer):$traceDirectory/live-apply.csv", (Join-Path $traceOutBeforeUnpause "live-apply.csv")))

    Resume-Batch
    $script:batchPausedHere = $false
    Write-Output "  batch-1 unpaused; recovery (if any) proceeds from here"

    # --- loss computation -----------------------------------------------------------------------------
    $appliedBeforeKill = New-Object 'System.Collections.Generic.HashSet[string]'
    Import-Csv -LiteralPath (Join-Path $traceOutBeforeUnpause "live-apply.csv") | ForEach-Object {
        if ([long]$_.appliedAtEpochMs -le $killAtContainerMs) {
            [void]$appliedBeforeKill.Add([string]$_.submissionId)
        }
    }
    $processedSet = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($id in $processedAfterRestart) { [void]$processedSet.Add([string]$id) }
    $lost = New-Object 'System.Collections.Generic.List[string]'
    foreach ($id in $appliedBeforeKill) {
        if (-not $processedSet.Contains($id)) { [void]$lost.Add($id) }
    }
    $events["appliedBeforeKillCount"] = $appliedBeforeKill.Count
    $events["lostCount"] = $lost.Count
    $events["lostSample"] = @($lost | Select-Object -First 30)
    Write-JsonFile -Path (Join-Path $artifacts "loss.json") -Object ([ordered]@{
            appliedBeforeKillCount = $appliedBeforeKill.Count
            processedAfterRestartCount = $processedAfterRestart.Count
            lostCount = $lost.Count
            lostSubmissionIds = @($lost)
        })
    Write-Output "  loss: $($lost.Count) of $($appliedBeforeKill.Count) pre-kill applies missing from the restarted processed set"

    # --- let the load (and recovery) run out, then check convergence ---------------------------------
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($holdSeconds + 120)
    while (-not $script:gatlingProcess.HasExited) {
        if ([DateTimeOffset]::UtcNow -gt $deadline) { throw "Gatling did not finish within its hold plus 120s." }
        Start-Sleep -Milliseconds 1000
    }
    $events["gatlingExitCode"] = $script:gatlingProcess.ExitCode
    Write-Output "  load ended (Gatling exit $($events['gatlingExitCode']))"

    $drained = $true
    try { [void](Wait-PipelineQuiescent -TimeoutSeconds $DrainTimeoutSeconds -Description "the pipeline after the load") }
    catch { $drained = $false; Write-Output "  drain: $($_.Exception.Message)" }
    $events["drained"] = $drained
    $final = Compare-LiveImpactUserTotals
    Write-JsonFile -Path (Join-Path $artifacts "consistency-final.json") -Object $final
    $events["finalConsistent"] = $final.Matches
    Write-Output "  final digest: consistent=$($final.Matches) ($($final.ApiParticipants) vs $($final.OracleParticipants) participants)"

    $traceOut = Join-Path $artifacts "trace"
    [void](New-Item -ItemType Directory -Path $traceOut -Force)
    [void](Invoke-Docker -Arguments @("cp", "$($config.BatchContainer):$traceDirectory/.", $traceOut))
    $judgedRows = Export-LiveImpactJudged -Path (Join-Path $artifacts "judged.csv")
    $events["judgedRowsExported"] = $judgedRows
    Write-JsonFile -Path (Join-Path $artifacts "container-limits.json") -Object (Get-LiveImpactContainerLimits)
    Write-JsonFile -Path (Join-Path $artifacts "w3-run-events.json") -Object $events

    $script:outcome = if ($drained -and $final.Matches) { "complete" } else { "measured-incomplete" }
    $exitCode = if ($script:outcome -eq "complete") { 0 } else { 2 }
}
catch {
    $script:outcome = "failed"
    Write-Output "  FAILED: $($_.Exception.Message)"
    Write-JsonFile -Path (Join-Path $artifacts "failure.json") -Object ([ordered]@{ message = $_.Exception.Message; position = $_.InvocationInfo.PositionMessage; events = $events })
    $exitCode = 1
}
finally {
    if ($script:batchPausedHere) {
        try { Resume-Batch } catch { Write-Output "  cleanup: resume batch-1 failed: $($_.Exception.Message)" }
    }
    if ($null -ne $script:gatlingProcess -and -not $script:gatlingProcess.HasExited) {
        try { $script:gatlingProcess.Kill() } catch { Write-Output "  cleanup: could not stop Gatling: $($_.Exception.Message)" }
    }
    if ($null -ne $script:seed) {
        try {
            $scope = [ordered]@{
                contestId = $config.ContestId
                seedPrefix = $config.SeedPrefix
                redisPattern = "$($config.ScoreboardKeyPrefix)$($config.ContestId):*"
                counts = Get-ExperimentScopeCounts
            }
            Write-JsonFile -Path (Join-Path $artifacts "cleanup-scope.json") -Object $scope
            Write-Output "  cleanup: contest $($scope.contestId), prefix '$($scope.seedPrefix)'"
            $removedKeys = Remove-ShortPauseKeys -Pattern $scope.redisPattern
            [void](Remove-ExperimentData -EvidenceDirectory $artifacts)
            Write-Output "  cleanup: removed this run's rows and $removedKeys scoreboard key(s)"
        }
        catch { Write-Output "  cleanup: $($_.Exception.Message)" }
    }
    Write-Output "  outcome: $($script:outcome) (exit $exitCode)"
}
exit $exitCode
