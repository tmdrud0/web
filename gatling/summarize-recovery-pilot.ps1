# The pilot's results, as one table: a median and a range per mode, from the runs that were measured.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File gatling\summarize-recovery-pilot.ps1 `
#     -SuiteDirectory var\scoreboard-recovery\20260921-101500-suite-pilot
#
# Three runs per mode is not a distribution, and this does not pretend it is one. What it reports is the
# middle run and the extent of the three, because with three runs the median and the range are the
# honest summaries - a mean would put a figure between two runs that happened and an interval that did
# not. A mode whose three runs disagree widely is saying something in that disagreement, which is why
# both numbers are reported and neither is used alone.
#
# Only runs that were measured are summarized. A run that produced no figures is listed with the reason
# it produced none, because dropping it silently would turn "two of three runs of this mode failed" into
# a clean-looking median over one.

[CmdletBinding()]
param(
    # The suite's directory, or any directory holding run artifact directories. When omitted, the newest
    # `*-suite-pilot` directory under the artifact root is used.
    [string]$SuiteDirectory,

    [string]$ArtifactRoot = "var\scoreboard-recovery",

    # Write the curated table here. Empty leaves the file alone, which is what a look before writing
    # wants.
    [string]$OutputDirectory = "docs\scoreboard-recovery-experiment\results",

    # Copied verbatim into the table, because a figure without the data it was measured on is not a
    # result. The run records carry these too; they are repeated here so the table can be read on its own.
    [int]$UserCount = 200,
    [int]$ProblemCount = 5,
    [double]$TargetRps = 40,
    [int]$HoldSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName

function Get-Number {
    param($Value)

    if ($null -eq $Value) { return $null }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text) -or $text -eq "unavailable") { return $null }
    $number = 0d
    if (-not [double]::TryParse($text, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        return $null
    }
    return $number
}

function Format-Cell {
    param($Value)

    if ($null -eq $Value) { return "unavailable" }
    return ([double]$Value).ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
}

# The middle of three, without interpolating between two of them. An even count takes the lower of the
# two middles, which is a value that was measured - the alternative is a figure no run produced.
function Get-Median {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][double[]]$Values)

    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    return $sorted[[int][math]::Floor(($sorted.Count - 1) / 2)]
}

function Get-ModeColumn {
    param(
        [Parameter(Mandatory = $true)][object[]]$Runs,
        [Parameter(Mandatory = $true)][string]$Column
    )

    $values = New-Object 'System.Collections.Generic.List[double]'
    $missing = 0
    foreach ($run in $Runs) {
        $number = Get-Number $run.$Column
        if ($null -eq $number) { $missing++; continue }
        $values.Add($number)
    }
    return [pscustomobject][ordered]@{
        Count = $values.Count
        Missing = $missing
        Median = Get-Median -Values $values.ToArray()
        Min = if ($values.Count -eq 0) { $null } else { ($values | Measure-Object -Minimum).Minimum }
        Max = if ($values.Count -eq 0) { $null } else { ($values | Measure-Object -Maximum).Maximum }
    }
}

# --- the runs ---------------------------------------------------------------------------------------

if ([string]::IsNullOrWhiteSpace($SuiteDirectory)) {
    $root = Join-Path $repoRoot $ArtifactRoot
    $newest = @(Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*-suite-pilot" } | Sort-Object LastWriteTime -Descending |
        Select-Object -First 1)
    if ($newest.Count -eq 0) {
        throw "No suite directory found under '$root'. Run gatling\run-recovery-pilot-suite.ps1 -Phase pilot first, or pass -SuiteDirectory."
    }
    $SuiteDirectory = $newest[0].FullName
}
elseif (-not [IO.Path]::IsPathRooted($SuiteDirectory)) {
    $SuiteDirectory = Join-Path $repoRoot $SuiteDirectory
}
if (-not (Test-Path -LiteralPath $SuiteDirectory)) {
    throw "Suite directory '$SuiteDirectory' does not exist."
}

$suiteMetadataPath = Join-Path $SuiteDirectory "suite-metadata.json"
if (-not (Test-Path -LiteralPath $suiteMetadataPath)) {
    throw "Suite directory '$SuiteDirectory' has no suite-metadata.json, so its conditions are unknown."
}
$suite = Get-Content -LiteralPath $suiteMetadataPath -Raw | ConvertFrom-Json

# The runs are read from their own artifact directories rather than from the suite's own table, so a
# column added to a run's summary appears here without this script being changed to carry it.
$runs = New-Object 'System.Collections.Generic.List[object]'
$unmeasured = New-Object 'System.Collections.Generic.List[object]'
foreach ($run in @($suite.runs)) {
    $artifactDirectory = [string]$run.artifactDirectory
    $summaryPath = if ([string]::IsNullOrWhiteSpace($artifactDirectory)) { "" } else { Join-Path $artifactDirectory "recovery-summary.csv" }
    if ([string]::IsNullOrWhiteSpace($summaryPath) -or -not (Test-Path -LiteralPath $summaryPath)) {
        $unmeasured.Add([pscustomobject][ordered]@{
                runId = [string]$run.runId
                mode = [string]$run.mode
                exitMeaning = [string]$run.exitMeaning
                reason = "no recovery-summary.csv; see $($run.stdout)"
            })
        continue
    }
    $rows = @(Import-Csv -LiteralPath $summaryPath)
    if ($rows.Count -ne 1) {
        throw "A run's recovery-summary.csv must have exactly one data row; '$summaryPath' has $($rows.Count)."
    }
    $row = $rows[0]
    # A run that could not be measured at all carries no figures; one measured but incomplete does, and
    # it is kept, because the way a mode breaks is a result. Which of the two it is is in `complete`.
    if ([string]$row.complete -ne "True" -and [string]$row.complete -ne "False") {
        $unmeasured.Add([pscustomobject][ordered]@{
                runId = [string]$run.runId
                mode = [string]$run.mode
                exitMeaning = [string]$run.exitMeaning
                reason = "its summary carries no verdict (complete='$($row.complete)')"
            })
        continue
    }
    $runs.Add($row)
}

$modes = @($suite.modes)
$summary = New-Object 'System.Collections.Generic.List[object]'
$modesPresent = New-Object 'System.Collections.Generic.List[string]'
foreach ($mode in $modes) {
    $modeRuns = @($runs | Where-Object { [string]$_.mode -eq $mode })
    if ($modeRuns.Count -eq 0) { continue }
    $modesPresent.Add($mode)
    $summary.Add([pscustomobject][ordered]@{ Mode = $mode; Runs = $modeRuns.ToArray() })
}

# --- the table ----------------------------------------------------------------------------------------

# Each row is one figure, with the three modes side by side: median and, in brackets, the range the runs
# actually spanned. Laid out this way because the question the pilot asks is comparative - which mode
# costs what - and a table per mode would make the reader do the comparison the summary exists to do.
$figures = @(
    [pscustomobject]@{ Name = "detectionLatencyMs"; Unit = "ms"; Note = "T_detected - T_fault: how long the batch role took to notice the rollback" }
    [pscustomobject]@{ Name = "consistencyOutageMs"; Unit = "ms"; Note = "T_consistent - T_fault: how long the scoreboard disagreed with MySQL" }
    [pscustomobject]@{ Name = "backlogDrainMs"; Unit = "ms"; Note = "T_backlog_drained - T_fault: how long until the pipeline was quiet" }
    [pscustomobject]@{ Name = "repairDurationMs"; Unit = "ms"; Note = "T_backlog_drained - T_consistent: the repair cost after the scoreboard was right" }
    [pscustomobject]@{ Name = "fullRecoveryMs"; Unit = "ms"; Note = "the later of the two ends, measured from the fault" }
    [pscustomobject]@{ Name = "recoveryLagP50Ms"; Unit = "ms"; Note = "new judged results reflected on the scoreboard while it was wrong, p50" }
    [pscustomobject]@{ Name = "recoveryLagP95Ms"; Unit = "ms"; Note = "the same, p95" }
    [pscustomobject]@{ Name = "recoveryLagMaxMs"; Unit = "ms"; Note = "the same, worst single result" }
    [pscustomobject]@{ Name = "baselineLagP95Ms"; Unit = "ms"; Note = "the same measurement over the window before the fault, p95" }
    [pscustomobject]@{ Name = "lagP95IncreaseMs"; Unit = "ms"; Note = "recovery p95 minus baseline p95" }
    [pscustomobject]@{ Name = "maxStreamPendingEvents"; Unit = "events"; Note = "the deepest the scoreboard's own backlog got" }
    [pscustomobject]@{ Name = "maxStreamOldestReadySeconds"; Unit = "s"; Note = "the age of the oldest unapplied event at its worst" }
    [pscustomobject]@{ Name = "minStreamQueueConsumers"; Unit = "consumers"; Note = "zero means the mode stopped its consumer; only stream-offset should" }
    [pscustomobject]@{ Name = "maxUnappliedResults"; Unit = "results"; Note = "judged results MySQL held that the scoreboard had not applied" }
    [pscustomobject]@{ Name = "pollsWithoutConsumer"; Unit = "polls"; Note = "how many polls found no consumer on the stream" }
    [pscustomobject]@{ Name = "appliedDeltaDuringRecovery"; Unit = "results"; Note = "how many results the scoreboard took while it was recovering" }
    [pscustomobject]@{ Name = "mysqlQuestionsDelta"; Unit = "queries"; Note = "MySQL Questions over the recovery window" }
    [pscustomobject]@{ Name = "mysqlRowsReadDelta"; Unit = "rows"; Note = "Innodb_rows_read over the recovery window" }
    [pscustomobject]@{ Name = "redisTotalCommandsDelta"; Unit = "commands"; Note = "Redis commands over the recovery window" }
    [pscustomobject]@{ Name = "redisEvalCallsDelta"; Unit = "calls"; Note = "scoreboard Lua evaluations over the recovery window" }
    [pscustomobject]@{ Name = "redisRestoreCallsDelta"; Unit = "calls"; Note = "RESTORE calls - the injector's own footprint, in every mode" }
    [pscustomobject]@{ Name = "redisDelCallsDelta"; Unit = "calls"; Note = "DEL calls - the injector's own footprint, in every mode" }
    [pscustomobject]@{ Name = "sequenceRoundsDelta"; Unit = "rounds"; Note = "redis-seq only: allocator rounds" }
    [pscustomobject]@{ Name = "sequenceReplayedDelta"; Unit = "results"; Note = "redis-seq only: results replayed from the duplicate window" }
    [pscustomobject]@{ Name = "rollbackObservedDelta"; Unit = "events"; Note = "how many times the consumer saw the offset go backwards" }
    [pscustomobject]@{ Name = "rollbackRestartsDelta"; Unit = "restarts"; Note = "how many times a mode stopped and restarted its consumer to repair" }
    [pscustomobject]@{ Name = "maxAppProcessCpu"; Unit = "ratio"; Note = "batch-1 process CPU at its peak" }
    [pscustomobject]@{ Name = "gatlingSuccessPercent"; Unit = "%"; Note = "the share of new submissions the ingress accepted" }
    [pscustomobject]@{ Name = "gatlingObservedP95Millis"; Unit = "ms"; Note = "the ingress's own p95 response time" }
    [pscustomobject]@{ Name = "gatlingFailedRequests"; Unit = "requests"; Note = "new submissions the ingress did not accept" }
)

$tablePath = Join-Path $SuiteDirectory "pilot-summary.csv"
if ($runs.Count -eq 0) {
    # Every run failed to be measured. There is no table to write, and a table of `unavailable`s would
    # look like a result; what there is, is the reasons.
    Write-Output "no run of this suite produced figures, so there is nothing to summarize."
    foreach ($run in $unmeasured) {
        Write-Output "  $($run.runId) ($($run.mode)): $($run.exitMeaning) - $($run.reason)"
    }
    exit 1
}
$builder = New-Object Text.StringBuilder
$header = @("figure", "unit", "note")
foreach ($mode in $modesPresent) { $header += @("$mode median", "$mode min", "$mode max", "$mode runs") }
[void]$builder.AppendLine(($header -join ","))
foreach ($figure in $figures) {
    $cells = @($figure.Name, $figure.Unit, $figure.Note)
    foreach ($mode in $modesPresent) {
        $modeRuns = @(($summary | Where-Object { $_.Mode -eq $mode })[0].Runs)
        $column = Get-ModeColumn -Runs $modeRuns -Column $figure.Name
        $cells += @((Format-Cell $column.Median), (Format-Cell $column.Min), (Format-Cell $column.Max), "$($column.Count)/$($modeRuns.Count)")
    }
    [void]$builder.AppendLine((@($cells | ForEach-Object {
                    if ([string]$_ -match '[,"]') { '"' + ([string]$_).Replace('"', '""') + '"' } else { [string]$_ }
                }) -join ","))
}
[IO.File]::WriteAllText($tablePath, $builder.ToString(), (New-Object Text.UTF8Encoding($false)))

# The per-run rows, unchanged, so a reader can see the three runs behind every median rather than having
# to trust the middle one.
$runsPath = Join-Path $SuiteDirectory "pilot-runs.csv"
$columns = @($runs[0].PSObject.Properties.Name)
$runBuilder = New-Object Text.StringBuilder
[void]$runBuilder.AppendLine(($columns -join ","))
foreach ($row in $runs) {
    [void]$runBuilder.AppendLine((@($columns | ForEach-Object {
                    $value = [string]$row.$_
                    if ($value -match '[,"]') { '"' + $value.Replace('"', '""') + '"' } else { $value }
                }) -join ","))
}
[IO.File]::WriteAllText($runsPath, $runBuilder.ToString(), (New-Object Text.UTF8Encoding($false)))

Write-Output "pilot summary: $tablePath"
Write-Output "per-run rows:  $runsPath"
Write-Output ""
Write-Output "$($runs.Count) run(s) summarized; $($unmeasured.Count) produced no figures."
if ($unmeasured.Count -gt 0) {
    Write-Output "runs with no figures (kept out of the medians, listed here):"
    foreach ($run in $unmeasured) {
        Write-Output "  $($run.runId) ($($run.mode)): $($run.exitMeaning) - $($run.reason)"
    }
}
Write-Output ""
Write-Output ("conditions: " + (@(
            "users=$UserCount", "problems=$ProblemCount", "targetRps=$TargetRps",
            "holdSeconds=$HoldSeconds", "pollIntervalSeconds=$($suite.pollIntervalSeconds)"
        ) -join " "))
Write-Output ""

$width = ($figures | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
foreach ($figure in $figures) {
    $line = "{0,-$width}  " -f $figure.Name
    foreach ($mode in $modesPresent) {
        $modeRuns = @(($summary | Where-Object { $_.Mode -eq $mode })[0].Runs)
        $column = Get-ModeColumn -Runs $modeRuns -Column $figure.Name
        $cell = if ($column.Count -eq 0) {
            "unavailable"
        }
        elseif ($column.Min -eq $column.Max) {
            "$(Format-Cell $column.Median) (n=$($column.Count))"
        }
        else {
            "$(Format-Cell $column.Median) [$(Format-Cell $column.Min)..$(Format-Cell $column.Max)]"
        }
        $line += "{0,28}" -f $cell
    }
    Write-Output $line
}
Write-Output ""
foreach ($mode in $modesPresent) {
    $modeRuns = @(($summary | Where-Object { $_.Mode -eq $mode })[0].Runs)
    $complete = @($modeRuns | Where-Object { [string]$_.complete -eq "True" }).Count
    Write-Output "$mode : $complete of $($modeRuns.Count) runs complete"
}

if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
    if (-not [IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory = Join-Path $repoRoot $OutputDirectory }
    if (-not (Test-Path -LiteralPath $OutputDirectory)) {
        [void](New-Item -ItemType Directory -Path $OutputDirectory -Force)
    }
    Copy-Item -LiteralPath $tablePath -Destination (Join-Path $OutputDirectory "summary.csv") -Force
    Copy-Item -LiteralPath $runsPath -Destination (Join-Path $OutputDirectory "runs.csv") -Force
    $suiteMetadataDestination = Join-Path $OutputDirectory "run-metadata.json"
    # The raw paths only mean something on this machine, so the copy records where the raw output is
    # rather than trying to make the copy self-contained.
    $documentation = [pscustomobject][ordered]@{
        suiteDirectory = $SuiteDirectory
        suiteDirectoryIsUntracked = $true
        note = "The raw per-poll samples, container logs and the load generator's reports are in the suite and run directories above. They are not committed: they are large, and this table is what they were reduced to."
        conditions = [pscustomobject][ordered]@{
            userCount = $UserCount
            problemCount = $ProblemCount
            targetRps = $TargetRps
            holdSeconds = $HoldSeconds
            pollIntervalSeconds = $suite.pollIntervalSeconds
            submitIntervalMillis = $suite.submitIntervalMillis
            rampSeconds = $suite.rampSeconds
            baselineResults = $suite.baselineResults
            tailResults = $suite.tailResults
            contestDurationMinutes = $suite.contestDurationMinutes
            dbName = $suite.dbName
        }
        gitHead = $suite.gitHead
        runs = @($runs | ForEach-Object {
                [pscustomobject][ordered]@{
                    runId = [string]$_.runId
                    mode = [string]$_.mode
                    runIndex = [string]$_.runIndex
                    complete = [string]$_.complete
                    completeReason = [string]$_.incompleteReasons
                    faultAtUtc = [string]$_.faultAtUtc
                    detectedAtUtc = [string]$_.detectedAtUtc
                    consistentAtUtc = [string]$_.consistentAtUtc
                    drainedAtUtc = [string]$_.drainedAtUtc
                    lostCount = [string]$_.lostCount
                    kProcessedCount = [string]$_.kProcessedCount
                    kCheckpoint = [string]$_.kCheckpoint
                    finalDigestMatches = [string]$_.finalDigestMatches
                    gitHead = [string]$_.gitHead
                }
            })
        runsWithNoFigures = $unmeasured.ToArray()
    }
    $documentation | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $suiteMetadataDestination -Encoding utf8
    Write-Output ""
    Write-Output "curated results written to $OutputDirectory (summary.csv, runs.csv, run-metadata.json)"
}
