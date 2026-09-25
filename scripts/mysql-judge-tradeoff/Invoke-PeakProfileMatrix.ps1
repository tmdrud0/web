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
    [string]$Custom = ""
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

$results = New-Object System.Collections.Generic.List[object]
foreach ($c in $conditions) {
    $name, $mode, $w1, $w2, $fault = $c
    if ($Only.Count -gt 0 -and $Only -notcontains $name) { continue }
    $runId = "peak-b$([int]$BaseRps)-$name-$Suffix"
    $log = Join-Path $logDir "$runId.log"
    $runArgs = @{
        DispatchMode = $mode; PeakProfile = $true; PeakBaseRps = $BaseRps
        WorkerCount = $w1; Judge2WorkerCount = $w2
        MySqlMaxInFlight = 4 * $w1; Judge2MaxInFlight = 4 * $w2; MySqlClaimBatchSize = 16
        MySqlClaimTimeout = "4s"; MySqlPollInterval = "100ms"; RabbitPrefetch = 1
        LatencySeed = $LatencySeed; WarmupTargetRps = 10; WarmupSeconds = 30
        UserCount = $userCount; BurstAuthRps = $authRps; BurstAuthSeconds = $authSeconds
        SteadyGuardSeconds = 0; DrainTimeoutSeconds = 900
        ResetMySqlVolume = $true; ResetBrokerAndCacheVolumes = $true; RunId = $runId
    }
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
