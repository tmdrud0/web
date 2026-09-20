[CmdletBinding()]
param(
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$harness = Join-Path $PSScriptRoot "Run-TradeoffExperiment.ps1"
$comparer = Join-Path $PSScriptRoot "Compare-NormalTimeoutRuns.ps1"
$resultsRoot = Join-Path $repoRoot "results\mysql-judge-tradeoff"

# Deliberately fixed at two runs. This driver is the executable experiment specification and must
# not grow a timeout or load sweep after observing either result.
$matrix = @(
    [ordered]@{
        runId = "saturation-mif16-timeout2500ms-rps110-20260920"
        timeout = "2500ms"
    },
    [ordered]@{
        runId = "saturation-mif16-timeout1s-rps110-20260920"
        timeout = "1s"
    }
)

$runDirectories = New-Object System.Collections.Generic.List[string]
$outcomes = New-Object System.Collections.Generic.List[object]
$dryRunSuffix = Get-Date -Format "yyyyMMdd-HHmmss"

foreach ($run in $matrix) {
    $effectiveRunId = if ($DryRun) { "dryrun-$($run.runId)-$dryRunSuffix" } else { $run.runId }
    Write-Output "=== START $effectiveRunId timeout=$($run.timeout) target=110 RPS ==="
    & $harness -DispatchMode mysql -NormalTimeout -TargetRps 110 `
        -WorkerCount 16 -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 `
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
        -OutputBaseName "duplicate-saturation-comparison"
    if ($LASTEXITCODE -ne 0) { throw "Duplicate saturation comparison failed with exit $LASTEXITCODE." }
}

$outcomes | Format-Table -AutoSize | Out-String | Write-Output
exit 0
