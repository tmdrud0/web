[CmdletBinding()]
param(
    [switch]$DryRun,
    # MIF/target load and the two run identities are parameterized so this driver can be reused for
    # a different max-in-flight/RPS pair (e.g. MIF64 near its own saturation point) without touching
    # the MIF16 matrix below, which stays the default and is unaffected by these additions.
    [int]$MySqlMaxInFlight = 16,
    [double]$TargetRps = 110,
    [string]$Timeout1 = "2500ms",
    [string]$RunId1 = "saturation-mif16-timeout2500ms-rps110-20260920",
    [string]$Timeout2 = "1s",
    [string]$RunId2 = "saturation-mif16-timeout1s-rps110-20260920",
    [string]$OutputBaseName = "duplicate-saturation-comparison"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$harness = Join-Path $PSScriptRoot "Run-TradeoffExperiment.ps1"
$comparer = Join-Path $PSScriptRoot "Compare-NormalTimeoutRuns.ps1"
$resultsRoot = Join-Path $repoRoot "results\mysql-judge-tradeoff"

# Two runs, same as the original MIF16 driver. Default parameter values reproduce that fixed matrix
# exactly; passing -MySqlMaxInFlight/-TargetRps/-Timeout1/-Timeout2/-RunId1/-RunId2 lets a caller
# reuse the same measurement-window and warm-up machinery for a different condition pair without
# editing this file. This driver is the executable experiment specification and must not grow a
# timeout or load sweep after observing either result.
$matrix = @(
    [ordered]@{
        runId = $RunId1
        timeout = $Timeout1
    },
    [ordered]@{
        runId = $RunId2
        timeout = $Timeout2
    }
)

$runDirectories = New-Object System.Collections.Generic.List[string]
$outcomes = New-Object System.Collections.Generic.List[object]
$dryRunSuffix = Get-Date -Format "yyyyMMdd-HHmmss"

foreach ($run in $matrix) {
    $effectiveRunId = if ($DryRun) { "dryrun-$($run.runId)-$dryRunSuffix" } else { $run.runId }
    Write-Output "=== START $effectiveRunId timeout=$($run.timeout) mif=$MySqlMaxInFlight target=$TargetRps RPS ==="
    & $harness -DispatchMode mysql -NormalTimeout -TargetRps $TargetRps `
        -WorkerCount 16 -MySqlMaxInFlight $MySqlMaxInFlight -MySqlClaimBatchSize 16 `
        -MySqlClaimTimeout $run.timeout -MySqlPollInterval 100ms `
        -LatencySeed 20260920 -WarmupSeconds 30 -RampSeconds 5 `
        -MeasurementSeconds 60 -SteadyGuardSeconds 3 -DrainTimeoutSeconds 600 `
        -UserCount 1000 -RunId $effectiveRunId -DryRun:$DryRun
    $exitCode = $LASTEXITCODE
    $runDirectory = Join-Path $resultsRoot $effectiveRunId
    $outcomes.Add([pscustomobject]@{
        runId = $effectiveRunId
        timeout = $run.timeout
        exitCode = $exitCode
        summaryWritten = Test-Path (Join-Path $runDirectory "summary.json")
        failureRecorded = Test-Path (Join-Path $runDirectory "failure.txt")
    })
    if (-not $DryRun) { $runDirectories.Add($runDirectory) }
    Write-Output "=== END $effectiveRunId exit=$exitCode ==="
    if ($exitCode -ne 0 -or (-not $DryRun -and -not (Test-Path (Join-Path $runDirectory "summary.json")))) {
        throw "Run $effectiveRunId did not produce a clean analyzed result. No automatic rerun will be attempted."
    }
}

if (-not $DryRun) {
    & $comparer -RunDirectory ([string[]]$runDirectories) -OutputDirectory $resultsRoot `
        -OutputBaseName $OutputBaseName
    if ($LASTEXITCODE -ne 0) { throw "Duplicate saturation comparison failed with exit $LASTEXITCODE." }
}

$outcomes | Format-Table -AutoSize | Out-String | Write-Output
exit 0
