# Runs a list of peak-profile conditions one after another, each on a freshly reset stack.
#
#   .\scripts\mysql-judge-tradeoff\Invoke-PeakProfileMatrix.ps1 -Plan base -Suffix 20260925
#
# One run at a time, so only one process ever drives the docker CLI. A run that fails is recorded and
# the matrix moves on; if the Docker engine stops answering between runs the matrix stops, because a
# crashed Docker Desktop is a finding to report, not something to retry through.
[CmdletBinding()]
param(
    [ValidateSet("base", "base-extra", "fault", "custom")][string]$Plan = "base",
    [string]$Suffix = (Get-Date -Format "yyyyMMdd"),
    [double]$BaseRps = 5,
    [long]$LatencySeed = 20260920,
    [string[]]$Only = @(),
    [string]$Custom = "",
    # sleep (default) reproduces the base experiment exactly. cpu switches both judge nodes to
    # CpuLoadProfileContestJudgement AND caps each judge node's container at cpus = its own worker
    # count (the "one judgement per core" premise for the CPU-load variant experiment) - overridable
    # per-condition below, but there is currently no reason to run cpu mode with a different limit.
    [ValidateSet("sleep", "cpu")][string]$JudgeMode = "sleep"
)
$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "Run-TradeoffExperiment.ps1"
$logDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path "results\mysql-judge-tradeoff\peak-matrix-logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# name, mode, judge-1 workers, judge-2 workers, fault
$conditions = switch ($Plan) {
    "base" { @(
        @("k542-rabbit-r1", "rabbit", 2, 2, $false), @("k542-mysql-r1", "mysql", 2, 2, $false),
        @("k678-mysql-r1", "mysql", 3, 2, $false), @("k678-rabbit-r1", "rabbit", 3, 2, $false),
        @("k814-rabbit-r1", "rabbit", 3, 3, $false), @("k814-mysql-r1", "mysql", 3, 3, $false),
        @("k542-mysql-r2", "mysql", 2, 2, $false), @("k542-rabbit-r2", "rabbit", 2, 2, $false),
        @("k542-rabbit-r3", "rabbit", 2, 2, $false), @("k542-mysql-r3", "mysql", 2, 2, $false)) }
    "base-extra" { @(
        @("k678-rabbit-r2", "rabbit", 3, 2, $false), @("k678-mysql-r2", "mysql", 3, 2, $false),
        @("k814-mysql-r2", "mysql", 3, 3, $false), @("k814-rabbit-r2", "rabbit", 3, 3, $false)) }
    "fault" { @(
        @("fault-k814-rabbit-r1", "rabbit", 3, 3, $true), @("fault-k814-mysql-r1", "mysql", 3, 3, $true)) }
    "custom" { @($Custom -split ';' | Where-Object { $_ } | ForEach-Object { $f = $_ -split ','; ,@($f[0], $f[1], [int]$f[2], [int]$f[3], [bool]::Parse($f[4])) }) }
}

# One prepared session per arrival: the expectation plus six Poisson deviations, logged in at 100/s.
$expected = 0.0
foreach ($seg in @(@(90, 1), @(60, 5), @(30, 10), @(60, 5), @(120, 1))) { $expected += $seg[0] * $seg[1] * $BaseRps }
$contexts = [math]::Ceiling($expected + 6 * [math]::Sqrt($expected) + 20)
$authRps = [math]::Max(100, [math]::Ceiling($contexts / 300))
$authSeconds = [int][math]::Ceiling($contexts / $authRps) + 2
$userCount = [int]([math]::Ceiling($authRps * $authSeconds) + 100)
# The seed endpoint batch-inserts UserCount rows (1000/batch, single transaction) then reads the id
# range back with a LIKE-prefix SELECT, and the phased-load path seeds two contests (warm-up and
# measurement) at this same UserCount. The base experiment's fixed 60s default was measured only up
# to ~6,400 users; scale it so the margin is checked rather than assumed at 14x the user count.
$seedTimeoutSeconds = [Math]::Max(60, [int][math]::Ceiling($userCount / 200.0) + 60)

$results = New-Object System.Collections.Generic.List[object]
foreach ($c in $conditions) {
    $name, $mode, $w1, $w2, $fault = $c
    if ($Only.Count -gt 0 -and $Only -notcontains $name) { continue }
    $runIdInfix = if ($JudgeMode -eq "cpu") { "b$([int]$BaseRps)cpu" } else { "b$([int]$BaseRps)" }
    $runId = "peak-$runIdInfix-$name-$Suffix"
    $log = Join-Path $logDir "$runId.log"
    # Claim batch must stay >= MIF (worker x 4) or a poll can only ever fill part of max-in-flight,
    # capping the node's claim rate at batch/poll-interval regardless of worker count. The base
    # experiment's fixed 16 always satisfied this (MIF was 8-12), so this keeps every B=5 run byte-
    # identical while scaling automatically for larger worker counts (B=70: MIF 112-168).
    $claimBatch = [Math]::Max(16, 4 * [Math]::Max($w1, $w2))
    $runArgs = @{
        DispatchMode = $mode; PeakProfile = $true; PeakBaseRps = $BaseRps
        WorkerCount = $w1; Judge2WorkerCount = $w2
        MySqlMaxInFlight = 4 * $w1; Judge2MaxInFlight = 4 * $w2; MySqlClaimBatchSize = $claimBatch
        MySqlClaimTimeout = "4s"; MySqlPollInterval = "100ms"; RabbitPrefetch = 1
        LatencySeed = $LatencySeed; WarmupTargetRps = 10; WarmupSeconds = 30
        UserCount = $userCount; BurstAuthRps = $authRps; BurstAuthSeconds = $authSeconds
        SeedTimeoutSeconds = $seedTimeoutSeconds
        SteadyGuardSeconds = 0; DrainTimeoutSeconds = 900
        ResetMySqlVolume = $true; ResetBrokerAndCacheVolumes = $true; RunId = $runId
        JudgeLatencyMode = $JudgeMode
    }
    if ($JudgeMode -eq "cpu") { $runArgs.JudgeCpus = "$w1"; $runArgs.Judge2Cpus = "$w2" }
    if ($fault) { $runArgs.PeakFaultKillAtSeconds = 120; $runArgs.PeakFaultRestartAtSeconds = 180; $runArgs.KilledNode = "judge-1" }
    $started = Get-Date
    Write-Host "[$($started.ToString('HH:mm:ss'))] $runId ..."
    $ok = $true
    try {
        & $runner @runArgs *> $log
        if ($LASTEXITCODE -ne 0) { $ok = $false }
    } catch {
        $ok = $false
        "MATRIX: $_" | Add-Content $log
    }
    $results.Add([pscustomobject]@{ runId = $runId; ok = $ok; minutes = [math]::Round(((Get-Date) - $started).TotalMinutes, 1) })
    Write-Host "  -> ok=$ok in $([math]::Round(((Get-Date) - $started).TotalMinutes, 1)) min"
    & docker info --format "{{.ServerVersion}}" *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Docker engine is not answering after $runId; stopping the matrix."
        break
    }
}
$results | Format-Table -AutoSize | Out-String | Write-Host
$results | ConvertTo-Json | Set-Content (Join-Path $logDir "matrix-$Plan-$Suffix.json") -Encoding utf8
