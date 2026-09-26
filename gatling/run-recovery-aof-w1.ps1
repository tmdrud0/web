# Report 3 (AOF cost), workload W1: write capacity of a destructive scoreboard rebuild against one of
# three Redis persistence conditions (no / everysec / always), with no live Gatling load running.
#
# One call = one run: seed ~TargetN judged results directly into MySQL for a *fresh* contest (so its
# scoreboard starts empty - see report3-aof.md's warning that replaying already-applied results makes
# the Lua script a no-op and the AOF cost read as zero), trigger the product's own destructive rebuild
# (ContestScoreboardRebuildEndpoint / actuator id `contestscoreboard`), and record what it cost: wall
# time (rows/s), Redis EVAL/EVALSHA cost (INFO commandstats delta), INFO persistence, LATENCY, memory,
# and whether Redis's container was OOM-killed. Cleans this run's own MySQL rows and Redis keys up
# afterwards through the harness's own seeder/injector primitives - it creates and deletes nothing else.
#
#   $env:DB_PASSWORD is not needed (StackMySql authenticates inside its own container).
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-aof-w1.ps1 `
#     -Condition no -RunIndex 1 -ResetMySqlVolume -RecreateAppTier
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-aof-w1.ps1 `
#     -Condition no -RunIndex 2
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-aof-w1.ps1 `
#     -Condition no -RunIndex 3
#
# -RecreateAppTier should be passed on the first run of every condition (including the very first "no"
# run): it force-recreates the app tier so its Redis client reconnects to the just-(re)started Redis
# container cleanly, and re-points nginx at it. It is not needed between the three repeats of one
# condition, where Redis itself is not recreated (`docker compose up -d` leaves a service alone when its
# resolved config has not changed).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet("no", "everysec", "always")][string]$Condition,
    [Parameter(Mandatory = $true)][int]$RunIndex,

    [long]$TargetN = 100000,
    [int]$UserCount = 10000,
    [int]$ProblemCount = 10,
    [int]$ContestDurationMinutes = 120,
    [int]$AcceptPermille = 400,

    [string]$ArtifactRoot = "var\scoreboard-recovery-aof-w1",
    [string]$JavaExe = "C:\Program Files\Java\jdk-17\bin\java.exe",
    [switch]$ResetMySqlVolume,
    [switch]$RecreateAppTier,
    [switch]$SkipCleanup,
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

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$runId = "aofw1{0}_r{1}_{2}" -f $Condition, $RunIndex, $stamp
$artifacts = Join-Path $repoRoot (Join-Path $ArtifactRoot $runId)

[void](Initialize-RecoveryExperiment `
        -WorktreeRoot $repoRoot -ArtifactDirectory $artifacts -RunId $runId `
        -Mode "stream-offset" -ProjectName "oj-loadtest" `
        -DbContainer "oj-loadtest-mysql" -DbName "oj_loadtest" -StackMySqlAuth `
        -JavaExe $JavaExe)
$config = Get-RecoveryConfig
$config.ComposeArgs = @($config.ComposeArgs) + @("-f", "compose.redis-persistence.yaml")

$env:REDIS_APPENDONLY = if ($Condition -eq "no") { "no" } else { "yes" }
$env:REDIS_APPENDFSYNC = if ($Condition -eq "always") { "always" } else { "everysec" }

$events = [ordered]@{
    runId = $runId
    condition = $Condition
    runIndex = $RunIndex
    gitHead = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    gitDirty = (@(& git -C $repoRoot status --porcelain 2>$null).Count -gt 0)
    targetN = $TargetN
    userCount = $UserCount
    problemCount = $ProblemCount
    acceptPermille = $AcceptPermille
    redisAppendonly = $env:REDIS_APPENDONLY
    redisAppendfsync = $env:REDIS_APPENDFSYNC
}

$script:seed = $null
$script:outcome = "failed"
$exitCode = 1

Write-Output "aof-w1 condition=$Condition run=$RunIndex ($runId)"
Write-Output "  artifacts: $artifacts"

try {
    # --- stack up --------------------------------------------------------------------------------
    [void](Invoke-Compose -Arguments @("up", "-d", "rabbitmq"))
    [void](Invoke-Compose -Arguments @("up", "-d", "redis"))
    Wait-ResetTargetsReady

    if ($ResetMySqlVolume) { Reset-StackMySqlVolume }
    $schema = Ensure-StackMySqlReady -WorktreeRoot $repoRoot -MigrationAppService $MigrationAppService
    $events["stackMySqlSchemaMigratedThisRun"] = $schema.Migrated

    [void](Invoke-Compose -Arguments (@("up", "-d") + (Get-PilotStartServices)))
    if ($RecreateAppTier) {
        [void](Invoke-Compose -Arguments @("up", "-d", "--force-recreate", "--no-deps", "web-1", "web-2", "batch-1", "judge-1", "judge-2"))
        Reset-EdgeRouting
    }
    Wait-PilotStackHealthy

    $redisIdentity = Assert-RedisIsDedicated
    Write-Output "  redis $($redisIdentity.container) is this project's ($($redisIdentity.image))"

    # --- verify the persistence condition actually took effect, and that no rewrite is in flight ---
    [void](Invoke-RedisText -RedisArguments @("CONFIG", "SET", "latency-monitor-threshold", "1"))
    [void](Invoke-RedisText -RedisArguments @("LATENCY", "RESET"))
    $persistBefore = Get-RedisInfoSection -Section "persistence" -Description "persistence state before the run"
    $events["aofEnabledConfigured"] = $persistBefore["aof_enabled"]
    $events["aofRewriteInProgressBefore"] = $persistBefore["aof_rewrite_in_progress"]
    $expectAof = if ($Condition -eq "no") { "0" } else { "1" }
    if ([string]$persistBefore["aof_enabled"] -ne $expectAof) {
        throw "Redis reports aof_enabled=$($persistBefore['aof_enabled']) for condition '$Condition' (expected $expectAof). The compose override did not take effect."
    }
    $confAppendonly = @(Invoke-RedisText -RedisArguments @("CONFIG", "GET", "appendonly")) | Select-Object -Last 1
    $confAppendfsync = @(Invoke-RedisText -RedisArguments @("CONFIG", "GET", "appendfsync")) | Select-Object -Last 1
    $events["confAppendonly"] = [string]$confAppendonly
    $events["confAppendfsync"] = [string]$confAppendfsync
    if ([string]$confAppendonly -ne $env:REDIS_APPENDONLY) {
        throw "CONFIG GET appendonly = '$confAppendonly', expected '$($env:REDIS_APPENDONLY)'."
    }
    $waitDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    while ([string]$persistBefore["aof_rewrite_in_progress"] -ne "0" -and [DateTimeOffset]::UtcNow -lt $waitDeadline) {
        Start-Sleep -Seconds 1
        $persistBefore = Get-RedisInfoSection -Section "persistence" -Description "persistence state before the run"
    }
    if ([string]$persistBefore["aof_rewrite_in_progress"] -ne "0") {
        throw "aof_rewrite_in_progress did not reach 0 within 120s; refusing to measure through a rewrite."
    }

    $residualRedisMemory = Get-RedisInfoSection -Section "memory" -Description "memory before the run"
    $events["usedMemoryBefore"] = $residualRedisMemory["used_memory"]

    # --- seed: contest, users, problems, then ~TargetN judged results written directly to MySQL ------
    $script:seed = New-ExperimentSeed -UserCount $UserCount -ProblemCount $ProblemCount `
        -ContestDurationMinutes $ContestDurationMinutes -EvidenceDirectory $artifacts
    Write-Output "  seeded contest $($script:seed.ContestId) with $($script:seed.UserCount) users, $($script:seed.ProblemCount) problems"
    $seeded = Add-LiveImpactSeedResults -Seed $script:seed -Count $TargetN -AcceptPermille $AcceptPermille
    Write-JsonFile -Path (Join-Path $artifacts "seed-results.json") -Object $seeded
    $events["contestId"] = $script:seed.ContestId
    $events["seededResults"] = $seeded.ResultRows
    Write-Output "  seeded $($seeded.ResultRows) judged result(s) in MySQL; scoreboard is still empty"

    $countersBefore = Get-LiveImpactCounters
    $commandStatsBefore = Get-RedisCommandStats -Info (Get-RedisInfoSection -Section "commandstats" -Description "commandstats before")

    # --- the destructive rebuild itself --------------------------------------------------------------
    # batch-1's worker healthcheck (`grep -aq java /proc/1/cmdline`) is satisfied long before Spring
    # context refresh reaches the actuator endpoint (see run-recovery-live-impact.ps1's own note on this),
    # so the first call here is retried rather than trusted on the first try.
    $readyDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
    $rebuildOutput = $null
    $lastRebuildError = $null
    while ($null -eq $rebuildOutput) {
        try {
            $rebuildStart = [DateTimeOffset]::UtcNow
            $rebuildOutput = Invoke-LiveImpactScoreboardRebuild
        }
        catch {
            $lastRebuildError = $_.Exception.Message
            if ([DateTimeOffset]::UtcNow -gt $readyDeadline) {
                throw "The rebuild endpoint did not answer within 120s of retrying: $lastRebuildError"
            }
            Start-Sleep -Seconds 3
        }
    }
    $rebuildEnd = [DateTimeOffset]::UtcNow
    $elapsedSeconds = ($rebuildEnd - $rebuildStart).TotalSeconds
    $events["rebuildOutput"] = $rebuildOutput
    $events["rebuildElapsedSeconds"] = $elapsedSeconds
    $events["rebuildRowsPerSecond"] = if ($elapsedSeconds -gt 0) { $TargetN / $elapsedSeconds } else { "unavailable" }
    Write-Output "  rebuild done in $([math]::Round($elapsedSeconds, 3))s ($([math]::Round($TargetN / [math]::Max($elapsedSeconds, 0.001), 1)) rows/s)"

    $countersAfter = Get-LiveImpactCounters
    $delta = Get-LiveImpactCounterDelta -Before $countersBefore -After $countersAfter
    foreach ($key in $delta.Keys) { $events["counters.$key"] = $delta[$key] }

    $commandStatsAfter = Get-RedisCommandStats -Info (Get-RedisInfoSection -Section "commandstats" -Description "commandstats after")
    foreach ($cmd in @("eval", "evalsha")) {
        $before = if ($commandStatsBefore.Contains($cmd)) { $commandStatsBefore[$cmd] } else { $null }
        $after = if ($commandStatsAfter.Contains($cmd)) { $commandStatsAfter[$cmd] } else { $null }
        if ($null -eq $after) { $events["redis.$cmd.callsDelta"] = 0L; continue }
        $callsAfter = [long]$after["calls"]
        $usecAfter = [long]$after["usec"]
        $callsBefore = if ($null -ne $before) { [long]$before["calls"] } else { 0L }
        $usecBefore = if ($null -ne $before) { [long]$before["usec"] } else { 0L }
        $callsDelta = $callsAfter - $callsBefore
        $usecDelta = $usecAfter - $usecBefore
        $events["redis.$cmd.callsDelta"] = $callsDelta
        $events["redis.$cmd.usecDelta"] = $usecDelta
        $events["redis.$cmd.usecPerCallThisRun"] = if ($callsDelta -gt 0) { [math]::Round($usecDelta / [double]$callsDelta, 2) } else { "unavailable" }
        $events["redis.$cmd.usecPerCallCumulative"] = if ($null -ne $after -and $after.Contains("usec_per_call")) { $after["usec_per_call"] } else { "unavailable" }
    }

    $persistAfter = Get-RedisInfoSection -Section "persistence" -Description "persistence state after the run"
    foreach ($key in @("aof_enabled", "aof_rewrite_in_progress", "aof_last_bgrewrite_status", "aof_last_write_status",
            "aof_delayed_fsync", "aof_current_size", "aof_base_size", "rdb_changes_since_last_save",
            "rdb_bgsave_in_progress", "rdb_last_bgsave_status")) {
        if ($persistAfter.Contains($key)) { $events["persist.$key"] = $persistAfter[$key] }
    }

    $memoryAfter = Get-RedisInfoSection -Section "memory" -Description "memory after the run"
    foreach ($key in @("used_memory", "used_memory_rss", "used_memory_peak", "mem_fragmentation_ratio")) {
        if ($memoryAfter.Contains($key)) { $events["memory.$key"] = $memoryAfter[$key] }
    }

    $latencyLatest = @(Invoke-RedisText -RedisArguments @("LATENCY", "LATEST"))
    Write-JsonFile -Path (Join-Path $artifacts "latency-latest.json") -Object $latencyLatest
    foreach ($eventName in @("aof-fsync-always", "aof-write", "fsync-in-progress-on-fork", "fork")) {
        $history = @(Invoke-RedisText -RedisArguments @("LATENCY", "HISTORY", $eventName))
        if ($history.Count -gt 0) {
            Write-JsonFile -Path (Join-Path $artifacts "latency-history-$eventName.json") -Object $history
            $events["latency.$eventName.sampleCount"] = [math]::Floor($history.Count / 2)
        }
    }

    $oomKilled = @(Invoke-Docker -Arguments @("inspect", "--format", "{{.State.OOMKilled}}", $config.RedisContainer)) | Select-Object -Last 1
    $events["redisOomKilled"] = [string]$oomKilled
    $memStats = @(Invoke-Docker -Arguments @("stats", "--no-stream", "--format", "{{.MemUsage}}|{{.MemPerc}}|{{.CPUPerc}}", $config.RedisContainer)) | Select-Object -Last 1
    $events["redisContainerStats"] = [string]$memStats

    # --- a light consistency check: does the rebuilt board actually carry TargetN participants? -----
    try {
        $digestApi = Get-ApiScoreboardDigest
        $events["postRebuildApiParticipants"] = $digestApi.Standings.Count
    }
    catch {
        $events["postRebuildApiParticipants"] = "unavailable: $($_.Exception.Message)"
    }

    Write-JsonFile -Path (Join-Path $artifacts "container-limits.json") -Object (Get-LiveImpactContainerLimits)
    $eventsPath = Join-Path $artifacts "w1-run-events.json"
    Write-JsonFile -Path $eventsPath -Object $events
    Write-Output "  events written to $eventsPath"

    $script:outcome = "complete"
    $exitCode = 0
}
catch {
    $script:outcome = "failed"
    Write-Output "  FAILED: $($_.Exception.Message)"
    Write-JsonFile -Path (Join-Path $artifacts "failure.json") -Object ([ordered]@{ message = $_.Exception.Message; position = $_.InvocationInfo.PositionMessage; events = $events })
    $exitCode = 1
}
finally {
    if ($null -ne $script:seed -and -not $SkipCleanup) {
        try {
            $scope = [ordered]@{
                contestId = $config.ContestId
                seedPrefix = $config.SeedPrefix
                redisPattern = "$($config.ScoreboardKeyPrefix)$($config.ContestId):*"
                counts = Get-ExperimentScopeCounts
                statements = @(Get-ExperimentTableScope | ForEach-Object { "DELETE FROM $($_.Table) WHERE $($_.Where)" })
            }
            Write-JsonFile -Path (Join-Path $artifacts "cleanup-scope.json") -Object $scope
            Write-Output "  cleanup: contest $($scope.contestId), prefix '$($scope.seedPrefix)'"
            $removedKeys = Remove-ShortPauseKeys -Pattern $scope.redisPattern
            [void](Remove-ExperimentData -EvidenceDirectory $artifacts)
            Write-Output "  cleanup: removed this run's rows and $removedKeys scoreboard key(s)"
        }
        catch { Write-Output "  cleanup: $($_.Exception.Message)" }
    }
    elseif ($SkipCleanup) {
        Write-Output "  cleanup skipped (-SkipCleanup): contest $($config.ContestId), prefix '$($config.SeedPrefix)'"
    }
    Write-Output "  outcome: $($script:outcome) (exit $exitCode)"
}
exit $exitCode
