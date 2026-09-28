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

# One run's value for one figure. Most figures are a column of the run row; a few are a ratio of two of
# them, because the cost counters are totals over a window whose length is the mode's own fault-to-
# consistent span and therefore differs between modes by design. Comparing those totals directly would
# credit the mode that recovered faster with doing less work. A ratio of two columns is a different
# quantity from a ratio of two medians, so it is taken per run and then summarized like any other figure.
function Get-RunFigureValue {
    param(
        [Parameter(Mandatory = $true)]$Run,
        [Parameter(Mandatory = $true)]$Figure
    )

    # Asked for by presence rather than read directly: under `Set-StrictMode -Version Latest` a figure that
    # declares no `Ratio` would throw on the read rather than answer "not a ratio".
    $ratio = $Figure.PSObject.Properties["Ratio"]
    if ($null -eq $ratio -or $null -eq $ratio.Value) { return Get-Number $Run.$($Figure.Name) }
    $numerator = Get-Number $Run.$($ratio.Value.Numerator)
    $denominator = Get-Number $Run.$($ratio.Value.Denominator)
    if ($null -eq $numerator -or $null -eq $denominator -or $denominator -le 0) { return $null }
    return $numerator / $denominator
}

function Get-ModeColumn {
    param(
        [Parameter(Mandatory = $true)][object[]]$Runs,
        [Parameter(Mandatory = $true)]$Figure
    )

    $values = New-Object 'System.Collections.Generic.List[double]'
    $missing = 0
    foreach ($run in $Runs) {
        $number = Get-RunFigureValue -Run $run -Figure $Figure
        if ($null -eq $number) { $missing++; continue }
        $values.Add([double]$number)
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

# The suite records the conditions its runs actually ran under. The parameters are kept so a reader can
# override what is printed, but when one is left alone the suite is authoritative: a parameter default
# that happens to differ from the suite would print conditions the runs did not run under - and this
# table's whole job is to be readable without the suite at hand.
$conditionUserCount = if ($PSBoundParameters.ContainsKey("UserCount")) { $UserCount } else { [int]$suite.userCount }
$conditionProblemCount = if ($PSBoundParameters.ContainsKey("ProblemCount")) { $ProblemCount } else { [int]$suite.problemCount }
$conditionTargetRps = if ($PSBoundParameters.ContainsKey("TargetRps")) { $TargetRps } else { [double]$suite.targetRps }
$conditionHoldSeconds = if ($PSBoundParameters.ContainsKey("HoldSeconds")) { $HoldSeconds } else { [int]$suite.holdSeconds }

# The runs are read from their own artifact directories rather than from the suite's own table, so a
# column added to a run's summary appears here without this script being changed to carry it.
#
# The suite's own stamp is read from its directory name. Every directory this harness writes - the suite's
# and each run's - begins with `yyyyMMdd-HHmmss` in this machine's local time, so two stamps compare as
# text in the order they were written, and that is the only thing available here that tells this suite's
# runs from an earlier suite's runs of the same ids.
$suiteStamp = ""
$suiteStampMatch = [regex]::Match([IO.Path]::GetFileName($SuiteDirectory), '^(\d{8}-\d{6})-')
if ($suiteStampMatch.Success) { $suiteStamp = $suiteStampMatch.Groups[1].Value }

$runs = New-Object 'System.Collections.Generic.List[object]'
$unmeasured = New-Object 'System.Collections.Generic.List[object]'
foreach ($run in @($suite.runs)) {
    $artifactDirectory = [string]$run.artifactDirectory
    # A row is this suite's only if its directory was written after this suite started. The suite records
    # the directory it resolved for each run, and for a run that produced no summary of its own that
    # resolution can land on an earlier suite's directory for the same run id - whose row then reads as a
    # measured run of this one, carrying another run's figures and another run's verdict. Checked at the
    # lookup as well, but checked here too, because this is the step whose output is read and because a
    # suite-metadata.json written before that fix is still on disk.
    $artifactStamp = ""
    $artifactStampMatch = [regex]::Match([IO.Path]::GetFileName($artifactDirectory), '^(\d{8}-\d{6})-')
    if ($artifactStampMatch.Success) { $artifactStamp = $artifactStampMatch.Groups[1].Value }
    if ($suiteStamp -ne "" -and ($artifactStamp -eq "" -or $artifactStamp -lt $suiteStamp)) {
        $unmeasured.Add([pscustomobject][ordered]@{
                runId = [string]$run.runId
                mode = [string]$run.mode
                exitMeaning = [string]$run.exitMeaning
                reason = "the directory it resolved to ('$artifactDirectory') was written before this suite started, so its summary belongs to an earlier suite that used the same run id"
            })
        continue
    }
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
    if ([string]$row.runId -ne [string]$run.runId) {
        $unmeasured.Add([pscustomobject][ordered]@{
                runId = [string]$run.runId
                mode = [string]$run.mode
                exitMeaning = [string]$run.exitMeaning
                reason = "the summary it read is for run '$($row.runId)', not '$($run.runId)'"
            })
        continue
    }
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
    # `$modeRuns` is already an array - `@(...)` above - and an array in PowerShell has no `ToArray`
    # method, so calling one threw the moment a measured run reached here. Every other `.ToArray()` in
    # this harness is on a `List[...]`, which does have it; this was the only one on an `@()`.
    $summary.Add([pscustomobject][ordered]@{ Mode = $mode; Runs = $modeRuns })
}

# How many runs the mode was *given*, which is the denominator every count below is reported against. The
# measured runs alone would make a mode that ran three times and measured once read as fully covered, so
# the planned count is taken from the suite's own run list and never allowed below the measured count.
$plannedByMode = [ordered]@{}
foreach ($mode in $modesPresent) {
    $planned = @($suite.runs | Where-Object { [string]$_.mode -eq $mode }).Count
    $measured = @(($summary | Where-Object { $_.Mode -eq $mode })[0].Runs).Count
    $plannedByMode[$mode] = if ($planned -lt $measured) { $measured } else { $planned }
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
    [pscustomobject]@{ Name = "recoveryWindowSeconds"; Unit = "s"; Note = "the span every cost delta below is a total over. It is the mode's own, so it differs by design - which is why the per-second figures exist" }
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
    [pscustomobject]@{ Name = "appliedPerSecondDuringRecovery"; Unit = "results/s"; Note = "the same per second of the mode's own recovery window"; Ratio = @{ Numerator = "appliedDeltaDuringRecovery"; Denominator = "recoveryWindowSeconds" } }
    [pscustomobject]@{ Name = "mysqlQuestionsDelta"; Unit = "queries"; Note = "MySQL Questions over the recovery window - a total, see the per-second rows" }
    [pscustomobject]@{ Name = "mysqlQuestionsPerSecond"; Unit = "queries/s"; Note = "MySQL Questions per second of the mode's own recovery window"; Ratio = @{ Numerator = "mysqlQuestionsDelta"; Denominator = "recoveryWindowSeconds" } }
    [pscustomobject]@{ Name = "mysqlRowsReadDelta"; Unit = "rows"; Note = "Innodb_rows_read over the recovery window - a total" }
    [pscustomobject]@{ Name = "mysqlRowsReadPerSecond"; Unit = "rows/s"; Note = "Innodb_rows_read per second of the mode's own recovery window"; Ratio = @{ Numerator = "mysqlRowsReadDelta"; Denominator = "recoveryWindowSeconds" } }
    [pscustomobject]@{ Name = "redisTotalCommandsDelta"; Unit = "commands"; Note = "Redis commands over the recovery window - a total" }
    [pscustomobject]@{ Name = "redisTotalCommandsPerSecond"; Unit = "commands/s"; Note = "Redis commands per second of the mode's own recovery window"; Ratio = @{ Numerator = "redisTotalCommandsDelta"; Denominator = "recoveryWindowSeconds" } }
    [pscustomobject]@{ Name = "redisEvalCallsDelta"; Unit = "calls"; Note = "scoreboard Lua evaluations over the recovery window - a total" }
    [pscustomobject]@{ Name = "redisEvalCallsPerSecond"; Unit = "calls/s"; Note = "scoreboard Lua evaluations per second of the mode's own recovery window"; Ratio = @{ Numerator = "redisEvalCallsDelta"; Denominator = "recoveryWindowSeconds" } }
    [pscustomobject]@{ Name = "redisRestoreCallsDelta"; Unit = "calls"; Note = "RESTORE calls - the injector's own footprint, in every mode" }
    [pscustomobject]@{ Name = "redisDelCallsDelta"; Unit = "calls"; Note = "DEL calls - the injector's own footprint, in every mode" }
    [pscustomobject]@{ Name = "rangeRecoveryMs"; Unit = "ms"; Note = "redis-seq (mysql-poll) only: T_range_completed - T_fault, until the lost (R, H] range was re-applied and completed" }
    [pscustomobject]@{ Name = "pollResumeMaxSeconds"; Unit = "s"; Note = "redis-seq (mysql-poll) only: rollback detection to the next poll batch applied - how long new results waited" }
    [pscustomobject]@{ Name = "pollRollbacksDelta"; Unit = "rollbacks"; Note = "redis-seq (mysql-poll) only: allocator-below-watermark detections; one per injected fault" }
    [pscustomobject]@{ Name = "pollRecoveryAppliedDelta"; Unit = "results"; Note = "redis-seq (mysql-poll) only: results re-applied from the bounded range" }
    [pscustomobject]@{ Name = "maxPendingRecoveryRanges"; Unit = "ranges"; Note = "redis-seq (mysql-poll) only: pending recovery ranges at their most" }
    [pscustomobject]@{ Name = "rollbackObservedDelta"; Unit = "events"; Note = "the two non-rewinding modes only: how many rollbacks the batch role answered by rebuilding in place. stream-offset rewinds instead and never records this counter, so its 0 here is the design, not a missed detection" }
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
        $column = Get-ModeColumn -Runs $modeRuns -Figure $figure
        $cells += @((Format-Cell $column.Median), (Format-Cell $column.Min), (Format-Cell $column.Max), "$($column.Count)/$($plannedByMode[$mode])")
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
            "users=$conditionUserCount", "problems=$conditionProblemCount", "targetRps=$conditionTargetRps",
            "holdSeconds=$conditionHoldSeconds", "pollIntervalSeconds=$($suite.pollIntervalSeconds)"
        ) -join " "))
Write-Output ""

$width = ($figures | ForEach-Object { $_.Name.Length } | Measure-Object -Maximum).Maximum
foreach ($figure in $figures) {
    $line = "{0,-$width}  " -f $figure.Name
    foreach ($mode in $modesPresent) {
        $modeRuns = @(($summary | Where-Object { $_.Mode -eq $mode })[0].Runs)
        $column = Get-ModeColumn -Runs $modeRuns -Figure $figure
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
    # The suite's runs are the denominator, not the ones that were measured. Counting the measured subset
    # makes a mode whose two other runs failed to measure read as "1 of 1 runs complete" - the opposite of
    # what the runs with no figures are there to say.
    $planned = $plannedByMode[$mode]
    $noFigures = $planned - $modeRuns.Count
    $qualifier = if ($noFigures -gt 0) { " ($noFigures produced no figures)" } else { "" }
    Write-Output "$mode : $complete of $planned runs complete$qualifier"
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
            userCount = $conditionUserCount
            problemCount = $conditionProblemCount
            targetRps = $conditionTargetRps
            holdSeconds = $conditionHoldSeconds
            pollIntervalSeconds = $suite.pollIntervalSeconds
            submitIntervalMillis = $suite.submitIntervalMillis
            rampSeconds = $suite.rampSeconds
            baselineResults = $suite.baselineResults
            tailResults = $suite.tailResults
            contestDurationMinutes = $suite.contestDurationMinutes
            dbName = $suite.dbName
            dbPort = $suite.dbPort
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
                    # The suite's commit rather than the row's. `recovery-summary.csv` has no gitHead
                    # column - the runner writes it once per run into that run's `run-metadata.json` - so
                    # reading it from the row asked for a property that does not exist and threw under
                    # StrictMode, after summary.csv and runs.csv had already been copied into the results
                    # directory. The curated set was left incomplete and the command exited non-zero at
                    # the last step of a summarisation that had otherwise succeeded.
                    gitHead = [string]$suite.gitHead
                }
            })
        runsWithNoFigures = $unmeasured.ToArray()
    }
    $documentation | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $suiteMetadataDestination -Encoding utf8
    Write-Output ""
    Write-Output "curated results written to $OutputDirectory (summary.csv, runs.csv, run-metadata.json)"
}
