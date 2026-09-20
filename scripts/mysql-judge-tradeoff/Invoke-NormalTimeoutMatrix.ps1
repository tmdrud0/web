# The six normal-timeout runs, in the order and at the rates the experiment specifies. Each is one
# invocation of the harness with its own RunId, run strictly one at a time: the harness holds a
# single Compose project, so two runs cannot overlap, and the order is the order the comparison
# reads. A failing run is recorded and the next one still starts - a failure leaves its own
# directory and reason behind, and the pre-flight check inside the next run refuses to start if the
# failure left unfinished outbox rows behind.
#
# Run 1 (mif 16, 2500ms) failed on a harness defect, not on the system under test, and the rule for
# a re-run after a code fix is a new RunId - so this table's first entry is that re-run's id and the
# failed directory `normal-mif16-timeout2500ms-20260920` stays on disk as the failed record.
[CmdletBinding()]
param(
    [string[]]$RunId = @(),
    [int]$WarmupSeconds = 30,
    [int]$MeasurementSeconds = 60,
    [int]$UserCount = 1000,
    [int]$DrainTimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$harness = Join-Path $PSScriptRoot "Run-TradeoffExperiment.ps1"

# Fixed by the experiment: 80 RPS at max-in-flight 16 and 100 at 64, both about 70% of the measured
# saturation for that value (116.296 and 147.963 RPS), so the run stays steady and the only variable
# is the claim timeout.
$matrix = @(
    [ordered]@{ runId = "normal-mif16-timeout2500ms-20260920-retry"; mif = 16; timeout = "2500ms"; rps = 80 },
    [ordered]@{ runId = "normal-mif16-timeout1s-20260920";            mif = 16; timeout = "1s";     rps = 80 },
    [ordered]@{ runId = "normal-mif16-timeout2s-20260920";            mif = 16; timeout = "2s";     rps = 80 },
    [ordered]@{ runId = "normal-mif64-timeout10s-20260920";           mif = 64; timeout = "10s";    rps = 100 },
    [ordered]@{ runId = "normal-mif64-timeout2s-20260920";            mif = 64; timeout = "2s";     rps = 100 },
    [ordered]@{ runId = "normal-mif64-timeout4s-20260920";            mif = 64; timeout = "4s";     rps = 100 }
)
if ($RunId.Count -gt 0) {
    $matrix = @($matrix | Where-Object { $RunId -contains $_.runId })
    if ($matrix.Count -eq 0) { throw "None of the requested run ids is in the matrix." }
}

$outcomes = New-Object System.Collections.Generic.List[object]
foreach ($run in $matrix) {
    Write-Output "=== START $($run.runId) mif=$($run.mif) timeout=$($run.timeout) rps=$($run.rps) at $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK') ==="
    $exitCode = 1
    try {
        & $harness -DispatchMode mysql -NormalTimeout -TargetRps $run.rps `
            -MySqlMaxInFlight $run.mif -MySqlClaimTimeout $run.timeout `
            -UserCount $UserCount -DrainTimeoutSeconds $DrainTimeoutSeconds `
            -WarmupSeconds $WarmupSeconds -MeasurementSeconds $MeasurementSeconds `
            -LatencySeed 20260920 -RunId $run.runId 2>&1 |
            ForEach-Object { "$_" } | Select-Object -Last 25 | ForEach-Object { Write-Output $_ }
        $exitCode = $LASTEXITCODE
    } catch {
        Write-Output "run $($run.runId) threw: $($_.Exception.Message)"
        $exitCode = 1
    }
    Write-Output "=== END $($run.runId) exit=$exitCode at $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK') ==="
    # The exit code is recorded, but success is judged from the artifacts rather than from it: a
    # run that reached the analysis wrote summary.json, and a run that failed wrote failure.txt
    # with its reason. That way a run whose exit code was lost in the pipeline above is still read
    # correctly, and a run that threw after writing a summary is not called a clean success.
    $runDirectory = Join-Path $repoRoot "results\mysql-judge-tradeoff\$($run.runId)"
    $outcomes.Add([pscustomobject]@{
        runId = $run.runId; mif = $run.mif; timeout = $run.timeout; rps = $run.rps; exitCode = $exitCode
        summaryWritten = (Test-Path (Join-Path $runDirectory "summary.json"))
        failureRecorded = (Test-Path (Join-Path $runDirectory "failure.txt"))
    })
}

Write-Output ""
Write-Output "=== MATRIX OUTCOMES ==="
foreach ($o in $outcomes) {
    Write-Output "$($o.runId) mif=$($o.mif) timeout=$($o.timeout) rps=$($o.rps) exit=$($o.exitCode) summary=$($o.summaryWritten) failure=$($o.failureRecorded)"
}
$failed = @($outcomes | Where-Object { -not $_.summaryWritten -or $_.failureRecorded })
Write-Output "runs: $($outcomes.Count), without a clean summary: $($failed.Count)"

# A caller that keys on the exit status must not read a failed matrix as a success. The per-run
# artifacts are the authoritative record and are written either way, but the status is what an
# unattended wrapper sees, so a run that produced no clean summary is reported as a failure here.
if ($failed.Count -gt 0) {
    exit 1
}
exit 0
