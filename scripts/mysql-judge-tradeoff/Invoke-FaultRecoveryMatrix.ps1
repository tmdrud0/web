[CmdletBinding()]
param(
    [switch]$DryRun,
    # Which of the three fixed conditions to EXECUTE, by their canonical run id. The default is all
    # three, which is the whole experiment. Selecting a subset does not shrink the comparison: the
    # conditions that are not executed are still resolved from their existing run directories and
    # handed to the comparer, and a condition with no completed run to reuse stops the driver before
    # it spends a measurement on the others. Without that, "run two of three" silently becomes a
    # two-condition comparison reported as the experiment.
    [string[]]$Condition = @()
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$harness = Join-Path $PSScriptRoot "Run-TradeoffExperiment.ps1"
$comparer = Join-Path $PSScriptRoot "Compare-FaultRecoveryRuns.ps1"
$resultsRoot = Join-Path $repoRoot "results\mysql-judge-tradeoff"
$outcomesPath = Join-Path $resultsRoot "fault-recovery-matrix-outcomes.json"

# Fixed at three runs, each executed exactly once. This driver is the executable specification of the
# fault-recovery comparison and must not grow a fourth condition, a different lease, a different
# offered rate, or a repeat of any run after seeing a result. The three candidates are the settings
# the earlier rounds left standing in steady state: the timeout-duplication round cleared 1s and 2s
# of being usable in service, and the capacity round left mif=16 around 116 RPS and mif=64 around 148
# RPS, so the load below stays clear of saturation and the variable under test is the lease.
#
# B and C are the same max-in-flight and the same offered rate, so they isolate the lease. A runs at a
# different max-in-flight and a different rate, so its raw RPS and percentiles are reported alongside
# normalized metrics rather than ranked against B and C on the raw numbers.
#
# `rerunRunId` exists on a condition whose earlier attempt was stopped by something outside the
# harness. A new run id may never be chosen automatically, so it is written here as a literal with
# its reason, reviewable in git like the rest of the specification; the interrupted directory is kept
# and named as excluded in the report rather than overwritten. `rerunReason` is the audit trail for
# why a second run id exists at all.
$matrix = @(
    [ordered]@{
        runId = "fault-mif16-timeout2500ms-rps80-20260920"
        maxInFlight = 16
        timeout = "2500ms"
        targetRps = 80
    },
    [ordered]@{
        runId = "fault-mif64-timeout4s-rps100-20260920"
        rerunRunId = "fault-mif64-timeout4s-rps100-rerun1-20260920"
        rerunReason = ("The first attempt was interrupted at 2026-09-20T14:35 by the host's " +
            "memory-pressure reaper, not by a harness defect: the process was hard-killed, so that " +
            "directory holds its raw artifacts but no summary.json and no failure.txt. It is kept and " +
            "listed as excluded with this reason. No number is derived from it.")
        maxInFlight = 64
        timeout = "4s"
        targetRps = 100
    },
    [ordered]@{
        runId = "fault-mif64-timeout10s-rps100-20260920"
        maxInFlight = 64
        timeout = "10s"
        targetRps = 100
    }
)

foreach ($tool in @($harness, $comparer)) {
    if (-not (Test-Path $tool)) { throw "Missing tool: $tool" }
}

# The harness records the commit, whether the tree was dirty, and the harness file's own hash for
# every run. That is only worth recording if the run happened on a clean tree: an uncommitted harness
# carried across a matrix is how earlier runs came to name a commit whose harness cannot reproduce
# them. Refusing here turns the provenance rule into something mechanical instead of a habit.
if (-not $DryRun) {
    $dirty = @(& git -C $repoRoot status --porcelain)
    if ($dirty.Count -gt 0) {
        $listed = ($dirty | ForEach-Object { "  $_" }) -join "`n"
        throw ("The working tree is not clean, so a run now would record provenance that cannot be " +
            "reproduced from its own commit. Commit or account for these changes first:`n$listed")
    }
}

# Which conditions this invocation executes, and where each condition's evidence lives. Selecting a
# subset is a deliberate act with an explicit run id behind it, never something inferred from what
# happens to be on disk, so the selection is resolved from the argument and the matrix alone.
if ($Condition.Count -eq 0) { $Condition = @($matrix | ForEach-Object { $_.runId }) }
$knownRunIds = @($matrix | ForEach-Object { $_.runId })
$unknownConditions = @($Condition | Where-Object { $knownRunIds -notcontains $_ })
if ($unknownConditions.Count -gt 0) {
    throw ("-Condition names run ids that are not in the fixed matrix: " +
        (($unknownConditions | ForEach-Object { "  $_" }) -join "`n") +
        "`nThe matrix is the specification of this experiment; a condition is added by committing it here, not by passing it in.")
}

# For a condition being executed the directory is its rerun run id when it has one, so a rerun can
# never land on top of the interrupted attempt it replaces. For a condition that is not being executed
# the directory is whichever of its run ids already holds a summary, preferring the rerun because that
# is the attempt which completed.
function Resolve-CompletedRunDirectory {
    param($Run)
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Run.Contains('rerunRunId')) { $candidates.Add($Run.rerunRunId) }
    $candidates.Add($Run.runId)
    foreach ($candidate in $candidates) {
        $path = Join-Path $resultsRoot $candidate
        if (Test-Path (Join-Path $path "summary.json")) { return $path }
    }
    $listed = ($candidates | ForEach-Object { "  " + (Join-Path $resultsRoot $_) }) -join "`n"
    throw ("Condition $($Run.runId) was not selected for execution and none of its run directories " +
        "holds a summary.json, so the comparison would be short a condition while still being reported " +
        "as the experiment. Run it, or point -Condition at conditions that have completed. Looked in:`n$listed")
}

$conditionStates = New-Object System.Collections.Generic.List[object]
foreach ($run in $matrix) {
    $executes = $Condition -contains $run.runId
    $effectiveRunId = $run.runId
    if ($run.Contains('rerunRunId')) { $effectiveRunId = $run.rerunRunId }
    $conditionStates.Add([pscustomobject]@{
        canonicalRunId = $run.runId
        executes = $executes
        effectiveRunId = $effectiveRunId
        runDirectory = Join-Path $resultsRoot $effectiveRunId
        hasRerun = $run.Contains('rerunRunId')
    })
}

# The harness records the commit, whether the tree was dirty, and the harness file's own hash for
# every run. That is only worth recording if the run happened on a clean tree: an uncommitted harness
# carried across a matrix is how earlier runs came to name a commit whose harness cannot reproduce
# them. Refusing here turns the provenance rule into something mechanical instead of a habit.
if (-not $DryRun) {
    $dirty = @(& git -C $repoRoot status --porcelain)
    if ($dirty.Count -gt 0) {
        $listed = ($dirty | ForEach-Object { "  $_" }) -join "`n"
        throw ("The working tree is not clean, so a run now would record provenance that cannot be " +
            "reproduced from its own commit. Commit or account for these changes first:`n$listed")
    }
}

# A run directory is the only copy of that run's raw data - the failed runs are kept, not deleted, and
# the raw CSVs are git-ignored. So this refuses rather than overwrites, and it checks every condition
# it is about to execute before starting any of them, so a partly-run matrix cannot be half-redone by
# accident. A condition that is not being executed is not held to this: it is reused, not written.
if (-not $DryRun) {
    $existing = New-Object System.Collections.Generic.List[string]
    foreach ($state in $conditionStates) {
        if (-not $state.executes) { continue }
        if (Test-Path $state.runDirectory) { $existing.Add($state.runDirectory) }
    }
    if ($existing.Count -gt 0) {
        $listed = ($existing | ForEach-Object { "  $_" }) -join "`n"
        throw ("This matrix has already been run for the conditions selected here; these run " +
            "directories already exist:`n$listed`n`n" +
            "The approved policy for a failed run is to fix the defect and re-run under a deliberately " +
            "chosen new RunId, and the report lists the failed run as excluded with its reason. This " +
            "driver will neither overwrite those directories nor choose a new RunId on your behalf; a " +
            "rerun run id is added to the matrix above and committed.")
    }
    if (Test-Path $outcomesPath) {
        Write-Warning "A previous matrix outcome file exists at $outcomesPath; it will be replaced."
    }
}

# Resolved before any measurement starts, so a condition that cannot contribute is reported now rather
# than after two runs' worth of load has been spent.
$reusedDirectories = New-Object System.Collections.Generic.List[string]
foreach ($state in $conditionStates) {
    if ($state.executes) { continue }
    $resolved = Resolve-CompletedRunDirectory -Run ($matrix | Where-Object { $_.runId -eq $state.canonicalRunId })
    $state | Add-Member -NotePropertyName reusedDirectory -NotePropertyValue $resolved
    $reusedDirectories.Add($resolved)
    Write-Output "=== REUSE $($state.canonicalRunId) from $resolved (not selected for execution) ==="
}

# An empty first line of an exception's formatted text is common, and the reason field is read by a
# human scanning a table, so take the first line that actually says something.
function Get-FirstLine {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return "(no detail captured)" }
    $lines = @($Text -split "[\r\n]+" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -eq 0) { return "(no detail captured)" }
    return [string]$lines[0]
}

# A run is clean only when three independent signals agree: the harness returned its own success exit
# code, it wrote the analyzed summary, and it recorded no failure. The sibling normal-timeout driver
# reads the first two only, which reports a run that failed after writing its summary - and so still
# holds a failure.txt - as a usable measurement. Exit code and summary presence are necessary but not
# sufficient; summary.json is written before the analyzer runs, and the analyzer's own failure is
# recorded in failure.txt with the script's success exit never reached.
function Get-RunVerdict {
    param(
        [string]$RunId,
        [string]$RunDirectory,
        [bool]$Threw,
        $ExitCode,
        [string]$ErrorText,
        [bool]$DryRun
    )
    $summaryWritten = Test-Path (Join-Path $RunDirectory "summary.json")
    $failureRecorded = Test-Path (Join-Path $RunDirectory "failure.txt")
    $reasons = New-Object System.Collections.Generic.List[string]
    if ($Threw) { $reasons.Add("the harness raised: $(Get-FirstLine $ErrorText)") }
    if ($failureRecorded) { $reasons.Add("failure.txt was recorded, so the harness reported a failure") }
    if (-not $Threw -and $ExitCode -ne 0) { $reasons.Add("the harness exited with code $ExitCode") }
    # A dry run deliberately never writes a summary - it starts no containers and no load - so demanding
    # one here judged every dry-run condition as failed and stopped the matrix after the first, leaving
    # the second and third conditions' parameters and rendered Compose config never validated. That
    # inverted the purpose of the mode: the dry run exists precisely to check all three before the real
    # runs, and it was checking one. What a dry run must leave behind instead is its own record that the
    # harness reached the dry-run branch with these parameters and rendered a config.
    if ($DryRun) {
        if (-not (Test-Path (Join-Path $RunDirectory "DRY_RUN.txt"))) {
            $reasons.Add("DRY_RUN.txt was not written, so the harness never reached its dry-run branch")
        }
        if (-not (Test-Path (Join-Path $RunDirectory "expected-plan.json"))) {
            $reasons.Add("expected-plan.json was not written, so the staging plan was never rendered")
        }
        if (-not (Test-Path (Join-Path $RunDirectory "compose-config.yaml"))) {
            $reasons.Add("compose-config.yaml was not written, so the Compose config was never rendered")
        }
    } else {
        if (-not $summaryWritten) { $reasons.Add("summary.json was not written, so the run was never analyzed") }
    }
    $clean = $reasons.Count -eq 0
    $reason = $null
    if (-not $clean) { $reason = ($reasons -join "; ") }
    # Two readings the run itself produced that are not harness defects and so do not stop the matrix,
    # but that the report has to state: whether the backlog ever came back inside its baseline within the
    # drain timeout, and whether the fault landed on real active work. Recorded here so the outcome table
    # carries them even if the report is read without the per-run summaries.
    $recoveryTimeout = $null
    $runValidForRecovery = $null
    if ($summaryWritten) {
        try {
            $summaryDoc = Get-Content (Join-Path $RunDirectory "summary.json") -Raw | ConvertFrom-Json
            if ($null -ne $summaryDoc.faultRecovery) {
                $recoveryTimeout = $summaryDoc.faultRecovery.recoveryTimeout
                $runValidForRecovery = $summaryDoc.faultRecovery.runValidForRecovery
            }
        } catch {
            $reasons.Add("summary.json could not be read back: $(Get-FirstLine $_.Exception.Message)")
            $clean = $false
            $reason = ($reasons -join "; ")
        }
    }
    [pscustomobject]@{
        runId = $RunId
        runDirectory = $RunDirectory
        clean = $clean
        threw = $Threw
        exitCode = $ExitCode
        # Under -DryRun these three describe "what the dry run checked", not a measurement: clean=True
        # there means the parameters rendered, and summaryWritten=False is expected rather than a reason.
        dryRun = $DryRun
        summaryWritten = $summaryWritten
        failureRecorded = $failureRecorded
        recoveryTimeout = $recoveryTimeout
        runValidForRecovery = $runValidForRecovery
        reason = $reason
    }
}

$runDirectories = New-Object System.Collections.Generic.List[string]
$outcomes = New-Object System.Collections.Generic.List[object]
$notAttempted = New-Object System.Collections.Generic.List[string]
$dryRunSuffix = Get-Date -Format "yyyyMMdd-HHmmss"

$executedStates = @($conditionStates | Where-Object { $_.executes })
for ($index = 0; $index -lt $executedStates.Count; $index++) {
    $state = $executedStates[$index]
    $run = $matrix | Where-Object { $_.runId -eq $state.canonicalRunId }
    $effectiveRunId = $state.effectiveRunId
    if ($DryRun) { $effectiveRunId = "dryrun-$($state.canonicalRunId)-$dryRunSuffix" }
    $runDirectory = Join-Path $resultsRoot $effectiveRunId
    Write-Output "=== START $effectiveRunId mif=$($run.maxInFlight) timeout=$($run.timeout) target=$($run.targetRps) RPS ==="

    $exitCode = $null
    $errorText = $null
    $threw = $false
    try {
        & $harness -DispatchMode mysql -FaultRecovery -TargetRps $run.targetRps `
            -WorkerCount 16 -MySqlMaxInFlight $run.maxInFlight -MySqlClaimBatchSize 16 `
            -MySqlClaimTimeout $run.timeout -MySqlPollInterval 100ms `
            -LatencySeed 20260920 -WarmupSeconds 30 -RampSeconds 5 `
            -MeasurementSeconds 300 -SteadyGuardSeconds 3 -DrainTimeoutSeconds 600 `
            -UserCount 1000 -KilledNode judge-1 -DownDurationSeconds 15 `
            -FaultMinSteadySeconds 30 -FaultTriggerWaitSeconds 15 `
            -FaultMinRunning 1 -FaultMinReserved 4 -FaultFallbackMinReserved 1 `
            -RunId $effectiveRunId -DryRun:$DryRun
        $exitCode = $LASTEXITCODE
    } catch {
        # The harness rethrows after writing failure.txt and preserving what it had already collected,
        # so letting the exception end the driver would lose the outcome table that names the condition
        # which failed. Capture it, record the verdict, then refuse to continue below.
        $threw = $true
        $errorText = ($_ | Out-String).Trim()
    }

    $verdict = Get-RunVerdict -RunId $effectiveRunId -RunDirectory $runDirectory `
        -Threw $threw -ExitCode $exitCode -ErrorText $errorText -DryRun $DryRun
    $verdict | Add-Member -NotePropertyName canonicalRunId -NotePropertyValue $state.canonicalRunId
    $verdict | Add-Member -NotePropertyName reusedFromEarlierRun -NotePropertyValue $false
    $outcomes.Add($verdict)

    if (-not $DryRun -and $verdict.clean) { $runDirectories.Add($runDirectory) }
    $endLine = "=== END $effectiveRunId clean=$($verdict.clean) ==="
    if (-not $verdict.clean) { $endLine = "=== END $effectiveRunId clean=False reason=$($verdict.reason) ===" }
    Write-Output $endLine

    if (-not $verdict.clean) {
        # No automatic rerun and no partial matrix: the remaining conditions are recorded as not
        # attempted, because a comparison missing one of the fixed three cannot answer the question
        # this experiment asks, and burning another measurement on it would only produce numbers that
        # the report would then have to set aside anyway.
        for ($rest = $index + 1; $rest -lt $executedStates.Count; $rest++) {
            $notAttempted.Add($executedStates[$rest].canonicalRunId)
        }
        break
    }
}

$failed = @($outcomes.ToArray() | Where-Object { -not $_.clean })

if (-not $DryRun) {
    $document = [ordered]@{
        generatedAt = [datetimeoffset]::UtcNow.ToString("o")
        gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
        conditions = $matrix
        conditionSelection = $Condition
        executedRunIds = @($executedStates | ForEach-Object { $_.effectiveRunId })
        reusedDirectories = $reusedDirectories.ToArray()
        outcomes = $outcomes.ToArray()
        notAttempted = $notAttempted.ToArray()
        allClean = ($failed.Count -eq 0)
        note = "A condition that is not clean is not retried by this driver. The failed run keeps its failure.txt and its preserved artifacts; the approved policy is to fix the defect and re-run under a new RunId chosen deliberately and written into the matrix above. A condition that was not selected for execution is reused from its existing directory rather than re-measured, and reusedDirectories names which directory that was, so a comparison assembled from runs taken at different times can be read as such."
    }
    $document | ConvertTo-Json -Depth 6 | Set-Content $outcomesPath -Encoding utf8
}

if ($failed.Count -gt 0) {
    $listed = ($failed | ForEach-Object { "  - $($_.runId): $($_.reason)" }) -join "`n"
    $suffixNote = ""
    if ($notAttempted.Count -gt 0) {
        $suffixNote = "`nNot attempted, because the matrix stopped at the first failed condition: " +
            (($notAttempted | ForEach-Object { "  - $_" }) -join "`n")
    }
    if ($DryRun) {
        throw ("The dry run did not validate every condition, so no measurement may start from these " +
            "parameters.`n$listed$suffixNote")
    }
    throw ("The fault-recovery matrix did not complete cleanly. No automatic rerun will be attempted, " +
        "and the comparison was not produced.`n$listed$suffixNote")
}

if (-not $DryRun) {
    # Every condition contributes, whether this invocation measured it or reused an earlier run of it.
    # The comparer is handed one directory per condition and refuses a directory without a summary, so
    # the comparison cannot come out short without saying so.
    $comparisonDirectories = New-Object System.Collections.Generic.List[string]
    foreach ($directory in $runDirectories) { $comparisonDirectories.Add($directory) }
    foreach ($directory in $reusedDirectories) { $comparisonDirectories.Add($directory) }
    if ($comparisonDirectories.Count -ne $matrix.Count) {
        throw ("The comparison would cover $($comparisonDirectories.Count) of the $($matrix.Count) fixed " +
            "conditions, so it would be reported as the experiment while answering a smaller question. " +
            "Directories gathered: " + (($comparisonDirectories | ForEach-Object { "  $_" }) -join "`n"))
    }
    & $comparer -RunDirectory ([string[]]$comparisonDirectories) -OutputDirectory $resultsRoot `
        -OutputBaseName "fault-recovery-comparison"
    if ($LASTEXITCODE -ne 0) { throw "Fault-recovery comparison failed with exit $LASTEXITCODE." }
}

$outcomes | Format-Table -AutoSize | Out-String | Write-Output
exit 0
