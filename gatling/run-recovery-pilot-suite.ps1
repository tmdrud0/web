# The scoreboard recovery pilot, run as one command.
#
#   $env:DB_PASSWORD = '<password>'
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-pilot-suite.ps1 -Phase calibration
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\run-recovery-pilot-suite.ps1 -Phase pilot
#
# Calibration first, with a short hold: it exists to find out whether the constants below are ones this
# machine can actually hold steady - three modes that converge inside the timeouts, no container killed,
# and a full-replay repair that costs measurably more than the tail it repairs. The pilot phase then
# runs the measurement that gets reported.
#
# Each run is a child process, one per `run-recovery-pilot.ps1`, so that a run which fails cannot leave
# state behind in this one: the runner owns the stack, the load generator and the database rows, and it
# ends by taking its own rows out. What this script adds is the two things a single run cannot do for
# itself - clearing the *other* runs' leftovers, so that every run starts from the same empty
# scoreboard, and putting the nine results side by side.
#
# Every run of a phase uses the same knob values. They are parameters rather than constants so that the
# calibration phase can pass shorter ones, and the values actually used are written into each run's
# `run-metadata.json` and into the suite's own record - a report that quotes a config it did not
# actually run is not a measurement.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet("calibration", "pilot")][string]$Phase,

    # Calibration is one run per mode; the pilot is `-Repeats` runs per mode.
    [int]$Repeats = 3,

    # --- the conditions, forwarded verbatim to every run --------------------------------------------
    [int]$UserCount = 200,
    [int]$ProblemCount = 5,
    [int]$ContestDurationMinutes = 90,
    [double]$TargetRps = 40,
    [long]$SubmitIntervalMillis = 5000,
    [int]$RampSeconds = 15,
    [int]$HoldSeconds = 240,
    [int]$BaselineResults = 60,
    [int]$BaselineWindowSeconds = 20,
    [int]$TailResults = 20,
    [int]$PollIntervalSeconds = 2,
    [int]$SettleTimeoutSeconds = 120,
    [int]$DrainTimeoutSeconds = 180,
    [int]$GatlingTimeoutSeconds = 900,
    [int]$IngressSloP95Millis = 60000,

    [string]$ArtifactRoot = "var\scoreboard-recovery",
    [string]$DbName = "oj_test",
    [switch]$Build,
    # Stop after the first run that could not be measured at all, so a broken prerequisite is diagnosed
    # once instead of nine times. Runs that were measured but came out incomplete do not stop the suite:
    # an incomplete run is one of its results.
    [switch]$StopOnFirstFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\lib\RecoveryExperiment.ps1"

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName
$modes = @("full-replay", "redis-seq", "stream-offset")

if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw "DB_PASSWORD is not set. Export it before running the suite; no part of this harness reads it from a file."
}

function Get-SuiteRunId {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][int]$RunIndex
    )

    return ("{0}_{1}" -f ($Mode -replace '-', ''), $RunIndex)
}

# The run ids this phase will use, in the order they will be used. Calibration takes index 0 so that its
# rows are never mistaken for one of the pilot's nine.
function Get-SuiteSchedule {
    $schedule = New-Object 'System.Collections.Generic.List[object]'
    $indices = if ($Phase -eq "calibration") { @(0) } else { @(1..$Repeats) }
    foreach ($mode in $modes) {
        foreach ($index in $indices) {
            $schedule.Add([pscustomobject][ordered]@{ Mode = $mode; RunIndex = $index; RunId = (Get-SuiteRunId -Mode $mode -RunIndex $index) })
        }
    }
    return $schedule.ToArray()
}

# Every run id of this phase, cleared before every run. A run that crashed leaves its contest, its
# problems and its users behind, and a leftover contest with stored results is not a neutral fact: the
# oracle refuses to compare against a scoreboard while a second contest has results, because full-replay
# would replay that contest and the other two modes would not. So clearing them is what makes the three
# modes comparable rather than merely sequential.
function Clear-SuiteLeftovers {
    param(
        [Parameter(Mandatory = $true)][object[]]$Schedule,
        [Parameter(Mandatory = $true)][string]$EvidenceDirectory
    )

    $cleared = New-Object 'System.Collections.Generic.List[object]'
    foreach ($entry in $Schedule) {
        [void](Initialize-RecoveryExperiment -WorktreeRoot $repoRoot `
                -ArtifactDirectory (Join-Path $EvidenceDirectory $entry.RunId) `
                -RunId $entry.RunId -Mode $entry.Mode -DbPassword $env:DB_PASSWORD -DbName $DbName)
        $record = Remove-ExperimentLeftovers -EvidenceDirectory (Join-Path $EvidenceDirectory $entry.RunId)
        if ($record.cleaned) {
            $cleared.Add([pscustomobject][ordered]@{
                    runId = $entry.RunId
                    totalDeleted = $record.removed.totalDeleted
                    evidence = (Join-Path $EvidenceDirectory $entry.RunId)
                })
        }
    }
    return $cleared.ToArray()
}

# The artifact directory a finished run wrote, found by the run id its directory name ends with. Read
# back from disk rather than passed from the child, so a run that died before it could report still
# leaves whatever it produced for the summary to find.
function Find-RunArtifacts {
    param([Parameter(Mandatory = $true)][string]$RunId)

    $root = Join-Path $repoRoot $ArtifactRoot
    if (-not (Test-Path -LiteralPath $root)) { return $null }
    $candidates = @(Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-$RunId" } |
        Sort-Object LastWriteTime -Descending)
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate.FullName "recovery-summary.csv")) {
            return $candidate.FullName
        }
    }
    if ($candidates.Count -gt 0) { return $candidates[0].FullName }
    return $null
}

function Read-RunSummary {
    param([Parameter(Mandatory = $true)][string]$ArtifactDirectory)

    $path = Join-Path $ArtifactDirectory "recovery-summary.csv"
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    $rows = @(Import-Csv -LiteralPath $path)
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

# The runner's exit codes, each of which means something different about the run and nothing about the
# mode: it says whether there is a measurement here at all.
function Get-ExitMeaning {
    param([Parameter(Mandatory = $true)][int]$ExitCode)

    switch ($ExitCode) {
        0 { return "complete" }
        2 { return "measured but incomplete" }
        1 { return "failed to measure" }
        default { return "exited $ExitCode" }
    }
}

$schedule = Get-SuiteSchedule
$suiteStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$suiteDirectory = Join-Path $repoRoot (Join-Path $ArtifactRoot "$suiteStamp-suite-$Phase")
[void](New-Item -ItemType Directory -Path $suiteDirectory -Force)
$preCleanDirectory = Join-Path $suiteDirectory "pre-clean"

Write-Output "scoreboard recovery pilot: $Phase"
Write-Output "  $($schedule.Count) run(s): $(@($schedule | ForEach-Object { $_.RunId }) -join ', ')"
Write-Output "  artifacts: $suiteDirectory"
Write-Output "  load: $TargetRps/s at a ${SubmitIntervalMillis}ms pace, ramp ${RampSeconds}s hold ${HoldSeconds}s"
Write-Output ""

# One pre-clean pass before anything runs, so the first run cannot be blocked by a previous suite's
# crash - and then one before every run, for the same reason between runs. Reported rather than silent:
# rows taken back are rows that were somebody's incomplete attempt.
$initialClear = Clear-SuiteLeftovers -Schedule $schedule -EvidenceDirectory $preCleanDirectory
if ($initialClear.Count -gt 0) {
    foreach ($entry in $initialClear) {
        Write-Output "  took back $($entry.totalDeleted) leftover row(s) from an earlier '$($entry.runId)'"
    }
    Write-Output ""
}

$results = New-Object 'System.Collections.Generic.List[object]'
$stopwatch = [Diagnostics.Stopwatch]::StartNew()

foreach ($entry in $schedule) {
    Write-Output "=== $($entry.RunId) ($($entry.Mode) #$($entry.RunIndex)) ==="

    $before = Clear-SuiteLeftovers -Schedule $schedule -EvidenceDirectory $preCleanDirectory
    foreach ($cleared in $before) {
        if ($cleared.runId -ne $entry.RunId) {
            Write-Output "  note: took back $($cleared.totalDeleted) leftover row(s) from a failed '$($cleared.runId)' first"
        }
    }

    $runArgumentList = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $PSScriptRoot "run-recovery-pilot.ps1"),
        "-Mode", $entry.Mode,
        "-RunIndex", $entry.RunIndex,
        "-UserCount", $UserCount,
        "-ProblemCount", $ProblemCount,
        "-ContestDurationMinutes", $ContestDurationMinutes,
        "-TargetRps", $TargetRps,
        "-SubmitIntervalMillis", $SubmitIntervalMillis,
        "-RampSeconds", $RampSeconds,
        "-HoldSeconds", $HoldSeconds,
        "-BaselineResults", $BaselineResults,
        "-BaselineWindowSeconds", $BaselineWindowSeconds,
        "-TailResults", $TailResults,
        "-PollIntervalSeconds", $PollIntervalSeconds,
        "-SettleTimeoutSeconds", $SettleTimeoutSeconds,
        "-DrainTimeoutSeconds", $DrainTimeoutSeconds,
        "-GatlingTimeoutSeconds", $GatlingTimeoutSeconds,
        "-IngressSloP95Millis", $IngressSloP95Millis,
        "-ArtifactRoot", $ArtifactRoot
    )
    if ($Build) { $runArgumentList += "-Build" }
    foreach ($argument in $runArgumentList) {
        if ($argument -match '\s') {
            throw ("A run argument contains whitespace, so Start-Process would pass it as two arguments: " +
                "'$argument'. The worktree path and the artifact root are the ones that can carry one.")
        }
    }

    $runStartedAt = [DateTimeOffset]::UtcNow
    $stdoutPath = Join-Path $suiteDirectory "$($entry.RunId)-stdout.txt"
    $stderrPath = Join-Path $suiteDirectory "$($entry.RunId)-stderr.txt"
    $process = Start-Process -FilePath "powershell.exe" -ArgumentList $runArgumentList -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    $elapsed = [math]::Round((([DateTimeOffset]::UtcNow) - $runStartedAt).TotalMinutes, 2)

    foreach ($line in @(Get-Content -LiteralPath $stdoutPath -ErrorAction SilentlyContinue)) {
        Write-Output "  $line"
    }

    $artifactDirectory = Find-RunArtifacts -RunId $entry.RunId
    $summaryRow = $null
    if ($null -ne $artifactDirectory) { $summaryRow = Read-RunSummary -ArtifactDirectory $artifactDirectory }

    $result = [pscustomobject][ordered]@{
        runId = $entry.RunId
        mode = $entry.Mode
        runIndex = $entry.RunIndex
        exitCode = $exitCode
        exitMeaning = Get-ExitMeaning -ExitCode $exitCode
        elapsedMinutes = $elapsed
        artifactDirectory = $artifactDirectory
        complete = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.complete }
        faultAtUtc = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.faultAtUtc }
        lostCount = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.lostCount }
        detectionLatencyMs = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.detectionLatencyMs }
        consistencyOutageMs = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.consistencyOutageMs }
        backlogDrainMs = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.backlogDrainMs }
        finalDigestMatches = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.finalDigestMatches }
        incompleteReasons = if ($null -eq $summaryRow) { "unavailable" } else { [string]$summaryRow.incompleteReasons }
        stdout = $stdoutPath
        stderr = $stderrPath
    }
    $results.Add($result)

    Write-Output ""
    Write-Output "  -> $($result.exitMeaning) in $elapsed min"
    if ($null -ne $summaryRow) {
        Write-Output "     lost=$($result.lostCount) detect=$($result.detectionLatencyMs)ms consistent=$($result.consistencyOutageMs)ms drained=$($result.backlogDrainMs)ms digest=$($result.finalDigestMatches)"
    }
    Write-Output ""

    if ($exitCode -eq 1 -and $StopOnFirstFailure) {
        Write-Output "stopping: '$($entry.RunId)' could not be measured, so the runs after it would not be comparable."
        break
    }
}

$stopwatch.Stop()

# The run record, as the suite saw it: one row per run with the outcome that decides whether its figures
# may be used. The full metric set is in each run's own recovery-summary.csv.
$suiteSummaryPath = Join-Path $suiteDirectory "suite-summary.csv"
$builder = New-Object Text.StringBuilder
$columns = @($results[0].PSObject.Properties.Name)
[void]$builder.AppendLine(($columns -join ","))
foreach ($result in $results) {
    [void]$builder.AppendLine((@($columns | ForEach-Object { ConvertTo-CsvField -Value ([string]$result.$_) }) -join ","))
}
[IO.File]::WriteAllText($suiteSummaryPath, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))

$conditions = [pscustomobject][ordered]@{
    phase = $Phase
    startedAtUtc = $suiteStamp
    elapsedMinutes = [math]::Round($stopwatch.Elapsed.TotalMinutes, 2)
    repeats = if ($Phase -eq "calibration") { 1 } else { $Repeats }
    modes = $modes
    userCount = $UserCount
    problemCount = $ProblemCount
    contestDurationMinutes = $ContestDurationMinutes
    targetRps = $TargetRps
    submitIntervalMillis = $SubmitIntervalMillis
    rampSeconds = $RampSeconds
    holdSeconds = $HoldSeconds
    baselineResults = $BaselineResults
    baselineWindowSeconds = $BaselineWindowSeconds
    tailResults = $TailResults
    pollIntervalSeconds = $PollIntervalSeconds
    settleTimeoutSeconds = $SettleTimeoutSeconds
    drainTimeoutSeconds = $DrainTimeoutSeconds
    gatlingTimeoutSeconds = $GatlingTimeoutSeconds
    ingressSloP95Millis = $IngressSloP95Millis
    dbName = $DbName
    gitHead = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    gitStatusPorcelain = @(& git -C $repoRoot status --porcelain 2>$null)
    runs = $results.ToArray()
}
$conditions | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $suiteDirectory "suite-metadata.json") -Encoding utf8

Write-Output "suite summary: $suiteSummaryPath"
Write-Output "suite record:  $(Join-Path $suiteDirectory 'suite-metadata.json')"
Write-Output ""

$unmeasured = @($results | Where-Object { $_.exitCode -eq 1 })
$incomplete = @($results | Where-Object { $_.exitCode -eq 2 -or $_.complete -eq "false" })
$measured = @($results | Where-Object { $_.exitCode -eq 0 })

Write-Output "$($measured.Count) complete, $($incomplete.Count) measured but incomplete, $($unmeasured.Count) not measured, of $($results.Count) run(s)."
if ($incomplete.Count -gt 0) {
    Write-Output "incomplete runs and why:"
    foreach ($result in $incomplete) {
        Write-Output "  $($result.runId): $($result.incompleteReasons)"
    }
}
if ($unmeasured.Count -gt 0) {
    Write-Output "runs that produced no figures:"
    foreach ($result in $unmeasured) {
        Write-Output "  $($result.runId): see $($result.stdout)"
    }
}

# A run that produced no figures at all is a different thing from a run whose figures describe a mode
# that broke its ingress, and only the first is a failure of the experiment. The second is one of its
# results, so it is reported and does not fail the suite.
if ($unmeasured.Count -gt 0) { exit 1 }
if ($incomplete.Count -gt 0) { exit 2 }
exit 0
