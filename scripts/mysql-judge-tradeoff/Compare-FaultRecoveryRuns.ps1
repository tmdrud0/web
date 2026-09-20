[CmdletBinding()]
param(
    # Fault-recovery run directories in the order the specification compares them, each already
    # analyzed by Analyze-TradeoffRun.ps1 so that summary.json carries the faultRecovery section.
    [Parameter(Mandatory = $true)][string[]]$RunDirectory,
    # Defaults to the repository's results folder rather than to the first run's own directory. The
    # run directories themselves live under results/ and are git-ignored, so a comparison written
    # beside them would be lost with them; the published comparison files for this experiment sit in
    # results/mysql-judge-tradeoff next to the staircase and normal-timeout comparisons instead.
    [string]$OutputDirectory = "",
    # The file names are specific to this experiment, for the same reason the sibling's are: three
    # comparers run against the same results tree and none of them may overwrite another's report.
    [ValidatePattern('^[A-Za-z0-9._-]+$')][string]$OutputBaseName = "fault-recovery-comparison"
)

$ErrorActionPreference = "Stop"

# The specification fixes the order of the comparison sections, and the order is load-bearing: the
# correctness question is answered before any timing is read, because a run whose row counts do not
# balance has no latency reading to compare. The list is written out here rather than left implicit
# in the heading strings below so the JSON can carry the same order the Markdown renders.
$sectionOrder = @(
    "correctness and integrity",
    "backlog recovery time",
    "reclaimed cohort L_total p95/p99/max",
    "fault/down cohort fast p95/p99",
    "throughput recovery time",
    "backlog peak",
    "unnecessary claim/DB cost",
    "operational complexity"
)

# The five fault-recovery cohorts, in the order they happen in a run. The letters are the ones the
# specification's cohort tables A-E refer to; the window each one covers is read from the summary's
# own basis text, never guessed from the name.
$cohortLetters = [ordered]@{
    "pre-fault-steady"      = "A"
    "fault-down-arrivals"   = "B"
    "reclaimed-after-fault" = "C"
    "post-restart-recovery" = "D"
    "post-recovery-steady"  = "E"
}

# The run-level cohorts, which partition the measurement contest differently and are quoted by the
# sections above rather than tabulated as A-E.
$generalCohorts = @("all", "pre-fault-normal", "fault-window", "killed-node-claimed", "post-fault-arrivals", "measurement-steady")

$runs = New-Object System.Collections.Generic.List[object]
$excluded = New-Object System.Collections.Generic.List[object]

# ---------------------------------------------------------------------------
# Readers.
#
# Every field below is read through Get-Field instead of dotted access, because a summary written by
# an older analyzer, or one whose phase died early, is missing whole branches. Dotted access on a
# missing branch yields $null silently for a property that does not exist, but throws for one whose
# parent is $null in some hosts and returns a *default* for a typed property - so the reader that
# answers "is this value in the summary at all" has to be explicit about existence.
# ---------------------------------------------------------------------------
function Get-Field {
    param($Object, [string[]]$Name)
    $current = $Object
    foreach ($segment in $Name) {
        if ($null -eq $current) { return $null }
        # A dictionary - including the [ordered]@{} blocks this script builds itself - exposes its
        # entries through indexing, not through PSObject.Properties, which for a dictionary lists the
        # dictionary's own members (Count, Keys, ...) and would report every key as absent.
        if ($current -is [System.Collections.IDictionary]) {
            if (-not $current.Contains($segment)) { return $null }
            $current = $current[$segment]
            continue
        }
        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) { return $null }
        $current = $property.Value
    }
    return $current
}

function ConvertTo-DoubleOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    $parsed = 0.0
    # Invariant culture with the Float style: a summary written on a machine whose culture uses a
    # comma for the decimal mark would otherwise parse "2.5" as 25 or refuse it outright, and the
    # refusal is silent - the value simply reads as unavailable.
    if (-not [double]::TryParse([string]$Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) { return $null }
    return $parsed
}

function ConvertTo-DateTimeOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    # [datetimeoffset]::Parse($null) throws rather than returning, and several anchors are legitimately
    # absent on a run that timed out, so every parse goes through here.
    $parsed = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($text, [ref]$parsed)) { return $null }
    return $parsed
}

function Get-SecondsBetween {
    param($From, $To)
    $fromValue = ConvertTo-DateTimeOrNull $From
    $toValue = ConvertTo-DateTimeOrNull $To
    if ($null -eq $fromValue -or $null -eq $toValue) { return $null }
    return [math]::Round(($toValue - $fromValue).TotalSeconds, 3)
}

# A ratio whose denominator can legitimately be zero (an offered load of zero, a max-in-flight of
# zero on a run whose parameters were never written) returns $null and records why. Returning a
# substituted value here would put a number in the report that no measurement produced.
function Get-Ratio {
    param($Numerator, $Denominator, $Missing, [string]$Label)
    $numeratorValue = ConvertTo-DoubleOrNull $Numerator
    $denominatorValue = ConvertTo-DoubleOrNull $Denominator
    if ($null -eq $numeratorValue -or $null -eq $denominatorValue) {
        if ($null -ne $Missing) { $Missing.Add("$Label - one side of the ratio is not in the summary") }
        return $null
    }
    if ($denominatorValue -eq 0) {
        if ($null -ne $Missing) { $Missing.Add("$Label - the denominator is zero, so the ratio is undefined rather than zero") }
        return $null
    }
    return [math]::Round($numeratorValue / $denominatorValue, 6)
}

# Reads a field and records its absence. The label is what the report will print, so a reader can
# tell a value that was never measured from one this script failed to find.
function Read-Field {
    param($Object, [string[]]$Name, [string]$Label, $Missing)
    $value = Get-Field -Object $Object -Name $Name
    if ($null -eq $value -and $null -ne $Missing) { $Missing.Add("$Label (summary.json path: $($Name -join '.'))") }
    return $value
}

# ---------------------------------------------------------------------------
# Formatters. Every one of them answers "unavailable" for $null rather than "-", "0" or an empty
# cell: a blank where a value exists and a blank where none was measured must not look the same.
# ---------------------------------------------------------------------------
function Format-Value {
    param($Value)
    if ($null -eq $Value) { return "unavailable" }
    if ($Value -is [bool]) { return $(if ($Value) { "yes" } else { "no" }) }
    return [string]$Value
}

function Format-Number {
    param($Value)
    $number = ConvertTo-DoubleOrNull $Value
    if ($null -eq $number) { return "unavailable" }
    return $number.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
}

function Format-Seconds {
    param($Value)
    $number = ConvertTo-DoubleOrNull $Value
    if ($null -eq $number) { return "unavailable" }
    return $number.ToString("0.###", [Globalization.CultureInfo]::InvariantCulture) + "s"
}

function Format-RatioPercent {
    param($Value)
    $number = ConvertTo-DoubleOrNull $Value
    if ($null -eq $number) { return "unavailable" }
    return ($number * 100).ToString("0.###", [Globalization.CultureInfo]::InvariantCulture) + "%"
}

# The timeout column is a string and it is not always in seconds: an exact 2.5s has to be written in
# milliseconds because Spring's DurationStyle simple form takes integer digits only. Stripping a
# trailing "s" and casting the rest therefore leaves "2500m", which a numeric sort cannot read, so
# the value is converted here instead. A bare number is milliseconds, which is what Spring Boot's
# simple duration style assumes when no unit is given.
function Get-TimeoutMillis {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $t = $Text.Trim()
    if ($t -match '^([+-]?\d+)(ms|s|m|h)?$') {
        $n = [double]$Matches[1]
        switch ($Matches[2]) {
            "ms" { return $n }
            "s" { return $n * 1000 }
            "m" { return $n * 60000 }
            "h" { return $n * 3600000 }
            default { return $n }
        }
    }
    if ($t -match '^[+-]?[pP]') {
        try { return ([System.Xml.XmlConvert]::ToTimeSpan($t)).TotalMilliseconds } catch { return $null }
    }
    return $null
}

function Get-IntervalSeconds {
    param($Text)
    $millis = Get-TimeoutMillis $Text
    if ($null -eq $millis) { return $null }
    return $millis / 1000.0
}

# ---------------------------------------------------------------------------
# Cohort readings.
#
# A cohort is either a full Get-LatencyClassSummary or the two-field unavailable form. Reading it
# through one helper keeps the two shapes from being confused downstream: a percentile read out of
# the unavailable form is $null, and $null is what the report prints.
# ---------------------------------------------------------------------------
function Get-LatencyReading {
    param($Cohort)
    if ($null -eq $Cohort) { return $null }
    $available = Get-Field -Object $Cohort -Name @('available')
    if ($null -ne $available -and -not [bool]$available) { return $null }
    $reading = [ordered]@{
        available = $true
        # The run-level cohorts carry no submissionCount (their L_* blocks carry a count instead),
        # so the sample count is taken from whichever of the two this cohort actually has.
        sampleCount = Get-Field -Object $Cohort -Name @('submissionCount')
        over5sRatio = Get-Field -Object $Cohort -Name @('over5sRatio')
        over10sRatio = Get-Field -Object $Cohort -Name @('over10sRatio')
        result = Get-Field -Object $Cohort -Name @('L_result_ms')
        scoreboard = Get-Field -Object $Cohort -Name @('L_scoreboard_ms')
        total = Get-Field -Object $Cohort -Name @('L_total_ms')
        fast = Get-Field -Object $Cohort -Name @('byLatencyClass', 'fast')
        slow = Get-Field -Object $Cohort -Name @('byLatencyClass', 'slow')
        window = Get-Field -Object $Cohort -Name @('window')
    }
    if ($null -eq $reading.sampleCount) { $reading.sampleCount = Get-Field -Object $reading.total -Name @('count') }
    return $reading
}

function Get-CohortUnavailableReason {
    param($Cohort)
    if ($null -eq $Cohort) { return "the cohort is not in this summary at all" }
    $reason = Get-Field -Object $Cohort -Name @('reason')
    if ($null -ne $reason) { return [string]$reason }
    return "the cohort is present but carries no reason"
}

# The deterministic latency profile makes L_result land on one of two steps, so a cohort percentile
# is not a continuous quantity and a difference smaller than a step is queueing, not a different
# class. The boundary is placed midway between the base step and the base-plus-slow-tail step.
function Get-LatencyClassLabel {
    param($Milliseconds, $BaseMillis, $SlowMillis)
    $value = ConvertTo-DoubleOrNull $Milliseconds
    $base = ConvertTo-DoubleOrNull $BaseMillis
    $slow = ConvertTo-DoubleOrNull $SlowMillis
    if ($null -eq $value) { return $null }
    if ($null -eq $base -or $null -eq $slow) { return "unknown" }
    if ($value -le ($base + ($slow / 2))) { return "fast" }
    return "slow"
}

# ---------------------------------------------------------------------------
# Sampling bands.
#
# The recovery anchors and the backlog peak are read from a sampler that ticks about once a second,
# so a difference smaller than the interval it ticked at cannot be told from the read timing. Each
# band is derived from the run's own recorded interval rather than from a constant, and a band that
# cannot be read stays $null so the verdict says "not called" instead of calling a winner.
# ---------------------------------------------------------------------------
function Get-TimeBandSeconds {
    param($SamplingIntervalMs)
    $millis = ConvertTo-DoubleOrNull $SamplingIntervalMs
    if ($null -eq $millis -or $millis -le 0) { return $null }
    # Two intervals, not one: an anchor recorded at the tick after an event can sit up to one
    # interval late on each side, so a one-interval band would call a one-tick disagreement real.
    return [math]::Round(2 * $millis / 1000.0, 3)
}

function Get-BacklogBandRows {
    param($TargetRps, $SamplingIntervalMs)
    $rps = ConvertTo-DoubleOrNull $TargetRps
    $millis = ConvertTo-DoubleOrNull $SamplingIntervalMs
    if ($null -eq $rps -or $null -eq $millis -or $millis -le 0) { return $null }
    # What arrives between two reads at the offered load: a peak difference below that cannot be
    # separated from where in the interval the peak happened to be sampled.
    return [math]::Round($rps * $millis / 1000.0, 3)
}

# A scalar verdict for a 4s-versus-10s difference. It returns text, and the numeric delta is kept
# beside it in the JSON, so a reader can re-derive the call from the two raw values.
function Get-DeltaVerdict {
    param($ValueA, $ValueB, $Band, [string]$Units, [bool]$LoadComparable, [string]$LabelA = "4s", [string]$LabelB = "10s")
    if (-not $LoadComparable) {
        return "not comparable: the two runs were not offered the same load, so a raw difference here is not a reading about the claim timeout"
    }
    $a = ConvertTo-DoubleOrNull $ValueA
    $b = ConvertTo-DoubleOrNull $ValueB
    if ($null -eq $a -or $null -eq $b) { return "unavailable: one of the two runs did not carry this value" }
    $delta = [math]::Round($a - $b, 3)
    $bandValue = ConvertTo-DoubleOrNull $Band
    if ($null -eq $bandValue) {
        return "delta $($delta)$Units, but the sampling band is not in the summary, so this difference is not called either way"
    }
    if ([math]::Abs($delta) -lt $bandValue) {
        return "inside the run's own sampling band (delta magnitude $([math]::Round([math]::Abs($delta), 3))$Units is under $($bandValue)$Units): no winner on this measurement"
    }
    $faster = $(if ($delta -lt 0) { $LabelA } else { $LabelB })
    return "delta $($delta)$Units, outside the sampling band ($($bandValue)$Units): $faster is lower/faster on this measurement"
}

# The same question for a latency percentile, where the band is not a sampling interval but one step
# of the deterministic profile, and where crossing a class is the only difference that means a
# different amount of judge work was done.
function Get-LatencyDeltaVerdict {
    param($ValueA, $ValueB, $BaseMillis, $SlowMillis, [bool]$LoadComparable, [string]$LabelA = "4s", [string]$LabelB = "10s")
    if (-not $LoadComparable) {
        return "not comparable: the two runs were not offered the same load, so a raw latency difference here is not a reading about the claim timeout"
    }
    $a = ConvertTo-DoubleOrNull $ValueA
    $b = ConvertTo-DoubleOrNull $ValueB
    if ($null -eq $a -or $null -eq $b) { return "unavailable: one of the two runs did not carry this value" }
    $delta = [math]::Round($a - $b, 3)
    $classA = Get-LatencyClassLabel -Milliseconds $a -BaseMillis $BaseMillis -SlowMillis $SlowMillis
    $classB = Get-LatencyClassLabel -Milliseconds $b -BaseMillis $BaseMillis -SlowMillis $SlowMillis
    if ($classA -eq $classB -and $classA -ne "unknown") {
        return "delta $($delta)ms, but both runs' p99 sit on the same deterministic latency class ($classA): the same amount of judge work was done and neither is faster"
    }
    $step = ConvertTo-DoubleOrNull $BaseMillis
    if ($null -ne $step -and $step -gt 0 -and [math]::Abs($delta) -lt $step) {
        return "inside one deterministic latency step ($($step)ms): no winner on this measurement"
    }
    if ($classA -eq "unknown" -or $classB -eq "unknown") {
        return "delta $($delta)ms, but the deterministic latency profile is not in parameters.json, so the class boundary cannot be placed and this difference is not called"
    }
    $faster = $(if ($delta -lt 0) { $LabelA } else { $LabelB })
    return "delta $($delta)ms across a latency-class boundary ($($LabelA)=$($classA), $($LabelB)=$($classB)): $faster is lower on this measurement"
}

# ---------------------------------------------------------------------------
# recovery-samples.csv.
#
# This is the one file outside summary.json the comparer is allowed to read, and only for the
# backlog peak: the summary carries the pre-fault backlog percentiles and the normalisation instant
# but not the peak the backlog reached, and the peak is exactly what an operator asks about. The
# columns are the harness's own (judgeBacklog/scoreboardPending), and the peak is a max over rows,
# not a re-derivation of anything else. When the file is absent the peak is unavailable, never
# recomputed from the throughput series.
# ---------------------------------------------------------------------------
function Get-BacklogSeriesReading {
    param([string]$RunPath, $Missing)
    $samplesPath = Join-Path $RunPath "recovery-samples.csv"
    if (-not (Test-Path $samplesPath)) {
        if ($null -ne $Missing) { $Missing.Add("backlog peak (recovery-samples.csv is not in the run directory, so neither judgeBacklog nor scoreboardPending was ever written to a file this comparer reads)") }
        return $null
    }
    $rows = @(Import-Csv $samplesPath)
    if ($rows.Count -eq 0) {
        if ($null -ne $Missing) { $Missing.Add("backlog peak (recovery-samples.csv is present but empty)") }
        return $null
    }
    $judgePeak = $null
    $scoreboardPeak = $null
    $judgePeakGrowth = $null
    $scoreboardPeakGrowth = $null
    $sampleCount = 0
    $previousAt = $null
    $previousJudge = $null
    $previousScoreboard = $null
    foreach ($row in $rows) {
        $at = ConvertTo-DateTimeOrNull $row.at
        $judge = ConvertTo-DoubleOrNull $row.judgeBacklog
        $scoreboard = ConvertTo-DoubleOrNull $row.scoreboardPending
        if ($null -eq $at) { continue }
        $sampleCount++
        if ($null -ne $judge) {
            if ($null -eq $judgePeak -or $judge -gt $judgePeak) { $judgePeak = $judge }
        }
        if ($null -ne $scoreboard) {
            if ($null -eq $scoreboardPeak -or $scoreboard -gt $scoreboardPeak) { $scoreboardPeak = $scoreboard }
        }
        if ($null -ne $previousAt) {
            $deltaSeconds = ($at - $previousAt).TotalSeconds
            if ($deltaSeconds -gt 0) {
                # The growth rate is the largest rise between two consecutive samples: the backlog
                # series is cumulative-unfinished rows, so a rise is rows that arrived faster than
                # they were finished, and the largest rise is the steepest part of the outage.
                if ($null -ne $judge -and $null -ne $previousJudge) {
                    $rate = ($judge - $previousJudge) / $deltaSeconds
                    if ($null -eq $judgePeakGrowth -or $rate -gt $judgePeakGrowth) { $judgePeakGrowth = $rate }
                }
                if ($null -ne $scoreboard -and $null -ne $previousScoreboard) {
                    $rate = ($scoreboard - $previousScoreboard) / $deltaSeconds
                    if ($null -eq $scoreboardPeakGrowth -or $rate -gt $scoreboardPeakGrowth) { $scoreboardPeakGrowth = $rate }
                }
            }
        }
        $previousAt = $at
        $previousJudge = $judge
        $previousScoreboard = $scoreboard
    }
    if ($sampleCount -eq 0) {
        if ($null -ne $Missing) { $Missing.Add("backlog peak (recovery-samples.csv has no row with a readable timestamp)") }
        return $null
    }
    $combinedPeak = $null
    if ($null -ne $judgePeak -and $null -ne $scoreboardPeak) { $combinedPeak = $judgePeak + $scoreboardPeak }
    return [ordered]@{
        source = "recovery-samples.csv"
        sampleCount = $sampleCount
        judgeBacklogPeak = $judgePeak
        scoreboardPendingPeak = $scoreboardPeak
        combinedPeak = $combinedPeak
        judgeBacklogPeakGrowthRowsPerSec = $(if ($null -eq $judgePeakGrowth) { $null } else { [math]::Round($judgePeakGrowth, 3) })
        scoreboardPendingPeakGrowthRowsPerSec = $(if ($null -eq $scoreboardPeakGrowth) { $null } else { [math]::Round($scoreboardPeakGrowth, 3) })
    }
}

# ---------------------------------------------------------------------------
# Per-run extraction.
# ---------------------------------------------------------------------------
foreach ($directory in $RunDirectory) {
    if (-not (Test-Path $directory)) {
        $excluded.Add([ordered]@{ runDirectory = $directory; runId = Split-Path -Leaf $directory; reason = "the run directory does not exist, so nothing was measured here" })
        continue
    }
    $runPath = (Resolve-Path $directory).Path
    $summaryPath = Join-Path $runPath "summary.json"
    if (-not (Test-Path $summaryPath)) {
        # An attempted run that failed and one that was never analyzed both lack summary.json, and
        # they are not the same thing: the first has a failure.txt with its reason and no amount of
        # re-analysis will produce numbers from it, so say which one this is rather than telling the
        # reader to run the analyzer.
        $failurePath = Join-Path $runPath "failure.txt"
        $reason = if (Test-Path $failurePath) {
            $failureReason = (@(Get-Content $failurePath) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
            "the run failed and was not measured: $failureReason (see failure.txt in the run directory)"
        } else {
            "the run has no summary.json; run Analyze-TradeoffRun.ps1 on it first"
        }
        $excluded.Add([ordered]@{ runDirectory = $runPath; runId = Split-Path -Leaf $runPath; reason = $reason })
        continue
    }
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    # Anything this run's summary does not carry is recorded with the path it was looked for at, so
    # the report can say which value is missing and why rather than only that it is.
    $missing = New-Object System.Collections.Generic.List[string]

    $fault = Get-Field -Object $summary -Name @('faultRecovery')
    if ($null -eq $fault) {
        $excluded.Add([ordered]@{
            runDirectory = $runPath; runId = $summary.runId
            reason = "the summary carries no faultRecovery section; it is not a fault-recovery run (or predates the fault-recovery harness)"
        })
        continue
    }

    # Two disqualifications, kept apart from the missing-summary case above because the run was in
    # fact measured: a run whose fault was injected without any observed work under it never had the
    # failure it claims to measure, and a run whose recovery exceeded the harness's own timeout was
    # cut off while still down. Either one still carries numbers, and those numbers would read like
    # a recovery result, so neither is reported as one.
    $runValidForRecovery = Get-Field -Object $fault -Name @('runValidForRecovery')
    if ($null -ne $runValidForRecovery -and -not [bool]$runValidForRecovery) {
        $excluded.Add([ordered]@{
            runDirectory = $runPath; runId = $summary.runId
            reason = "faultRecovery.runValidForRecovery is false: the fault was not injected under observed work (faultNotInjectedWithActiveWork=$(Format-Value (Get-Field -Object $fault -Name @('faultNotInjectedWithActiveWork')))), so the run does not measure a recovery"
        })
        continue
    }
    $recoveryTimeout = Get-Field -Object $fault -Name @('recoveryTimeout')
    if ($null -eq $recoveryTimeout) { $recoveryTimeout = Get-Field -Object $summary -Name @('events', 'recoveryTimeout') }
    if ($null -ne $recoveryTimeout -and [bool]$recoveryTimeout) {
        $excluded.Add([ordered]@{
            runDirectory = $runPath; runId = $summary.runId
            reason = "recoveryTimeout is true: the run was cut off at the harness's recovery deadline while the node had not recovered, so its backlog never normalised and its recovery times are censored rather than measured"
        })
        continue
    }

    $parametersPath = Join-Path $runPath "parameters.json"
    $parameters = $null
    if (Test-Path $parametersPath) { $parameters = Get-Content $parametersPath -Raw | ConvertFrom-Json }

    # The max-in-flight and the claim timeout are read from the summary first and from parameters.json
    # only as a fallback: the summary is the analyzer's own reading of the run, while parameters.json
    # is what the harness intended to run, and on a run whose analysis was regenerated the two can
    # disagree. Which one answered is recorded so a reader can see it.
    $maxInFlight = Get-Field -Object $summary -Name @('staircase', 'mysqlMaxInFlightPerNode')
    $claimTimeout = Get-Field -Object $summary -Name @('staircase', 'mysqlClaimTimeout')
    $claimBatchSize = Get-Field -Object $summary -Name @('staircase', 'mysqlClaimBatchSize')
    $workerCount = Get-Field -Object $summary -Name @('staircase', 'workerCountPerNode')
    $configurationSource = "summary.staircase"
    if ($null -eq $maxInFlight -or $null -eq $claimTimeout) {
        $maxInFlight = Get-Field -Object $parameters -Name @('mysqlMaxInFlightPerNode')
        $claimTimeout = Get-Field -Object $parameters -Name @('mysqlClaimTimeout')
        $claimBatchSize = Get-Field -Object $parameters -Name @('mysqlClaimBatchSize')
        $workerCount = Get-Field -Object $parameters -Name @('workerCountPerNode')
        $configurationSource = "parameters.json (summary.staircase did not carry them)"
    }
    if ($null -eq $maxInFlight -or $null -eq $claimTimeout) {
        $missing.Add("max-in-flight and/or claim timeout (neither summary.staircase nor parameters.json carries them)")
        $configurationSource = "unavailable"
    }

    $stageRps = @(Get-Field -Object $summary -Name @('staircase', 'stageRps'))
    $offeredRps = $(if ($stageRps.Count -gt 0) { $stageRps[0] } else { Get-Field -Object $parameters -Name @('targetRps') })
    $samplingIntervalMs = Get-Field -Object $summary -Name @('staircase', 'samplingInterval', 'meanIntervalMs')
    $timeBandSeconds = Get-TimeBandSeconds -SamplingIntervalMs $samplingIntervalMs
    $backlogBandRows = Get-BacklogBandRows -TargetRps $offeredRps -SamplingIntervalMs $samplingIntervalMs

    # --- integrity -------------------------------------------------------------------------------
    $countNames = @("accepted", "uniqueSubmissions", "results", "scoreboardApplied")
    $counts = [ordered]@{}
    $missingCounts = New-Object System.Collections.Generic.List[string]
    foreach ($countName in $countNames) {
        # The name is read into a local before the inner read: inside the loop the outer objects are
        # the summary and the counts block, and a nested filter that used $_ would silently be
        # looking at the inner collection instead, yielding zero as if the count were absent.
        $countValue = Get-Field -Object $summary -Name @('counts', $countName)
        $counts[$countName] = $countValue
        if ($null -eq $countValue) { $missingCounts.Add($countName) }
        if ($null -eq $countValue) { $missing.Add("counts.$countName") }
    }
    $counts["completedHttpRequests"] = Get-Field -Object $summary -Name @('counts', 'completedHttpRequests')

    # The chain is the whole correctness claim: every accepted submission is a distinct submission,
    # every one of them produced a result row, and every result row was applied to the scoreboard.
    # It is evaluated as one comparison rather than four counts so a reader cannot check three and
    # miss the fourth.
    $chainHolds = $null
    if ($missingCounts.Count -eq 0) {
        $chainHolds = ([double]$counts.accepted -eq [double]$counts.uniqueSubmissions) -and
            ([double]$counts.accepted -eq [double]$counts.results) -and
            ([double]$counts.accepted -eq [double]$counts.scoreboardApplied)
    }
    $integrityPassed = [bool](Get-Field -Object $summary -Name @('integrity', 'passed'))
    $integrityReason = $null
    if ($missingCounts.Count -gt 0) {
        $integrityReason = "verification counts are missing for $($missingCounts.ToArray() -join ', '), so the chain accepted = uniqueSubmissions = results = scoreboardApplied could not be evaluated"
    } elseif (-not $integrityPassed) {
        $integrityReason = "integrity failed: lostOrIncomplete=$(Format-Value (Get-Field -Object $summary -Name @('integrity','lostOrIncomplete'))), finalResultMismatch=$(Format-Value (Get-Field -Object $summary -Name @('integrity','finalResultMismatch')))"
    }

    # HTTP outcomes for a fault-recovery run. A staircase run carries them under
    # staircase.stages[].http; this experiment measures a single offered rate, so the stages array is
    # empty and there is no such block. Nothing is inferred from a log file this comparer was not
    # told about - the run's own unavailable[] list is the reason printed instead.
    $httpStages = New-Object System.Collections.Generic.List[object]
    foreach ($stage in @(Get-Field -Object $summary -Name @('staircase', 'stages'))) {
        $stageHttp = Get-Field -Object $stage -Name @('http')
        if ($null -ne $stageHttp) { $httpStages.Add($stageHttp) }
    }
    $ko429 = $null
    $ko500 = $null
    $ko503 = $null
    if ($httpStages.Count -gt 0) {
        $sum429 = 0
        $sum500 = 0
        $sum503 = 0
        $complete = $true
        foreach ($stageHttp in $httpStages.ToArray()) {
            $value429 = Get-Field -Object $stageHttp -Name @('ko429')
            $value500 = Get-Field -Object $stageHttp -Name @('ko500')
            $value503 = Get-Field -Object $stageHttp -Name @('ko503')
            if ($null -eq $value429 -or $null -eq $value500 -or $null -eq $value503) { $complete = $false; continue }
            $sum429 += [int]$value429
            $sum500 += [int]$value500
            $sum503 += [int]$value503
        }
        if ($complete) {
            $ko429 = $sum429
            $ko500 = $sum500
            $ko503 = $sum503
        }
    }
    if ($null -eq $ko429) {
        $missing.Add("HTTP 429/500/503 counts (a fault-recovery run offers one rate, so its summary has no staircase.stages[].http block to sum; the stated reason is in the run's own staircase.unavailable[])")
    }

    # --- recovery times --------------------------------------------------------------------------
    $recoveryTimes = Get-Field -Object $fault -Name @('recoveryTimes')
    $anchors = Get-Field -Object $fault -Name @('anchors')
    $normalization = Get-Field -Object $fault -Name @('normalization')
    $recovery = Get-Field -Object $summary -Name @('recovery')

    $tStale = Read-Field -Object $recoveryTimes -Name @('T_staleSeconds') -Label "first stale reclaim time (T_stale)" -Missing $missing
    if ($null -eq $tStale) { $tStale = Read-Field -Object $recovery -Name @('firstStaleReclaimSeconds') -Label "first stale reclaim time (recovery.firstStaleReclaimSeconds)" -Missing (New-Object System.Collections.Generic.List[string]) }
    $tRestartRequested = Read-Field -Object $recoveryTimes -Name @('T_restartRequestedSeconds') -Label "restart requested time (T_restartRequested)" -Missing $missing
    $tContainerRunning = Read-Field -Object $recoveryTimes -Name @('T_containerRunningSeconds') -Label "container running time (T_container_running)" -Missing $missing
    $tNodeReady = Read-Field -Object $recoveryTimes -Name @('T_nodeReadySeconds') -Label "node ready time (T_node_ready)" -Missing $missing
    $tThroughput = Read-Field -Object $recoveryTimes -Name @('T_throughputRecoverySeconds') -Label "throughput recovery time" -Missing $missing
    $tBacklogNormalization = Read-Field -Object $recoveryTimes -Name @('T_backlogNormalizationSeconds') -Label "backlog normalization time (combined)" -Missing $missing
    $tLastReclaimedResult = Read-Field -Object $recoveryTimes -Name @('T_lastReclaimedResultSeconds') -Label "T_last_reclaimed_result" -Missing $missing
    $tLastReclaimedScoreboard = Read-Field -Object $recoveryTimes -Name @('T_lastReclaimedScoreboardSeconds') -Label "T_last_reclaimed_scoreboard" -Missing $missing
    $tDrain = $null
    $drainSeconds = Get-Field -Object $summary -Name @('events', 'drainSeconds')
    if ($null -eq $drainSeconds) { $drainSeconds = Get-Field -Object $summary -Name @('staircase', 'drainSeconds') }
    if ($null -eq $drainSeconds) { $drainSeconds = Get-Field -Object $recoveryTimes -Name @('drainSeconds') }
    if ($null -eq $drainSeconds) { $missing.Add("drain seconds (events.drainSeconds, staircase.drainSeconds and recoveryTimes.drainSeconds are all absent)") }
    $tJudgeNormalization = Read-Field -Object $normalization -Name @('judgeSecondsAfterFault') -Label "judge backlog normalization time" -Missing $missing
    $tScoreboardNormalization = Read-Field -Object $normalization -Name @('scoreboardSecondsAfterFault') -Label "scoreboard backlog normalization time" -Missing $missing

    # --- cohorts ---------------------------------------------------------------------------------
    # Two cohort families are read. The fault-recovery cohorts partition the measurement contest
    # around the fault and are the ones the comparison's sections quote; the run-level cohorts are
    # the analyzer's own partition and are reported beside them because two of them
    # (post-fault-arrivals, all) are the whole-fault views a reader will look for first.
    $faultCohortSource = Get-Field -Object $fault -Name @('cohorts')
    $faultCohorts = [ordered]@{}
    foreach ($cohortName in $cohortLetters.Keys) {
        # The name is read into a local before the read below so that the inner lookup cannot be
        # shadowed by the outer collection's own $_.
        $letter = $cohortLetters[$cohortName]
        $cohort = Get-Field -Object $faultCohortSource -Name @($cohortName)
        $faultCohorts[$cohortName] = [ordered]@{
            letter = $letter
            reading = Get-LatencyReading -Cohort $cohort
            unavailableReason = $(if ($null -eq (Get-LatencyReading -Cohort $cohort)) { Get-CohortUnavailableReason -Cohort $cohort } else { $null })
        }
        if ($null -eq (Get-LatencyReading -Cohort $cohort) -and $null -eq $cohort) {
            $missing.Add("faultRecovery.cohorts.$cohortName")
        }
    }
    $generalCohortSource = Get-Field -Object $summary -Name @('cohorts')
    $generalCohortReadings = [ordered]@{}
    foreach ($cohortName in $generalCohorts) {
        $cohort = Get-Field -Object $generalCohortSource -Name @($cohortName)
        $generalCohortReadings[$cohortName] = [ordered]@{
            reading = Get-LatencyReading -Cohort $cohort
            unavailableReason = $(if ($null -eq (Get-LatencyReading -Cohort $cohort)) { Get-CohortUnavailableReason -Cohort $cohort } else { $null })
        }
    }

    # --- throughput and the pre-fault baseline ---------------------------------------------------
    $throughput = Get-Field -Object $fault -Name @('throughput')
    $preFault = Get-Field -Object $fault -Name @('preFault')
    $preFaultResultRps = Read-Field -Object $throughput -Name @('preFaultResultRps') -Label "pre-fault result RPS (faultRecovery.throughput.preFaultResultRps)" -Missing (New-Object System.Collections.Generic.List[string])
    if ($null -eq $preFaultResultRps) {
        $preFaultResultRps = Get-Field -Object $preFault -Name @('resultRps')
        if ($null -eq $preFaultResultRps) { $missing.Add("pre-fault result RPS (faultRecovery.preFault.resultRps)") }
    }

    # --- kill-time state -------------------------------------------------------------------------
    $trigger = Get-Field -Object $fault -Name @('trigger')
    $claimedUnfinished = Get-Field -Object $fault -Name @('claimedUnfinishedAtKill')
    $reclaimAccounting = Get-Field -Object $fault -Name @('reclaimAccounting')
    $workCost = Get-Field -Object $summary -Name @('workCost')

    # The upper bound is the analyzer's cluster-wide reading and never the killed node's own claims:
    # the outbox has no claimed_by column, so every PUBLISHING row at kill time is counted whichever
    # node held it. The division below uses the two-node max-in-flight because this experiment runs
    # two judge containers, which is also why the divisor is doubled.
    $strandedUpperBound = Get-Field -Object $claimedUnfinished -Name @('clusterWideUpperBound')
    if ($null -eq $strandedUpperBound) { $strandedUpperBound = Get-Field -Object $workCost -Name @('clusterWideClaimedUnfinishedUpperBound') }
    if ($null -eq $strandedUpperBound) { $missing.Add("cluster-wide claimed unfinished upper bound (faultRecovery.claimedUnfinishedAtKill.clusterWideUpperBound and workCost.clusterWideClaimedUnfinishedUpperBound are both absent)") }
    $maxInFlightClusterWide = $null
    $maxInFlightValue = ConvertTo-DoubleOrNull $maxInFlight
    if ($null -ne $maxInFlightValue) { $maxInFlightClusterWide = $maxInFlightValue * 2 }
    $strandedShare = Get-Ratio -Numerator $strandedUpperBound -Denominator $maxInFlightClusterWide -Missing $missing -Label "stranded claimed rows / max-in-flight"

    # --- backlog peak ----------------------------------------------------------------------------
    $backlogSeries = Get-BacklogSeriesReading -RunPath $runPath -Missing $missing
    $preFaultJudgeP95 = Get-Field -Object $preFault -Name @('judgeBacklogP95')
    $preFaultScoreboardP95 = Get-Field -Object $preFault -Name @('scoreboardPendingP95')
    $preFaultCombinedP95 = $null
    if ($null -ne $preFaultJudgeP95 -and $null -ne $preFaultScoreboardP95) {
        $preFaultCombinedP95 = $preFaultJudgeP95 + $preFaultScoreboardP95
    }
    $judgePeak = $null
    $scoreboardPeak = $null
    $combinedPeak = $null
    if ($null -ne $backlogSeries) {
        $judgePeak = $backlogSeries.judgeBacklogPeak
        $scoreboardPeak = $backlogSeries.scoreboardPendingPeak
        $combinedPeak = $backlogSeries.combinedPeak
    }

    # --- normalized metrics ----------------------------------------------------------------------
    # Each one divides a quantity this summary carries by another quantity this summary carries, and
    # each returns $null with a recorded reason when either side is missing or the denominator is
    # zero. A zero substituted for a missing numerator here would read as "no extra cost", which is
    # the opposite of what an unmeasured value means.
    $acceptedRps = $preFaultResultRps
    $normalized = [ordered]@{
        acceptedRps = $acceptedRps
        backlogPeakOverAcceptedRps = Get-Ratio -Numerator $combinedPeak -Denominator $acceptedRps -Missing $missing -Label "backlog peak / accepted RPS"
        judgeBacklogPeakOverAcceptedRps = Get-Ratio -Numerator $judgePeak -Denominator $acceptedRps -Missing $missing -Label "judge backlog peak / accepted RPS"
        scoreboardPendingPeakOverAcceptedRps = Get-Ratio -Numerator $scoreboardPeak -Denominator $acceptedRps -Missing $missing -Label "scoreboard pending peak / accepted RPS"
        recoveredRowsPerSecond = Get-Ratio -Numerator (Get-Field -Object $reclaimAccounting -Name @('reclaimedRowsAfterFault')) -Denominator $tBacklogNormalization -Missing $missing -Label "recovered rows / second"
        backlogDrainRowsPerSecond = Get-Ratio -Numerator $(if ($null -ne $combinedPeak -and $null -ne $preFaultCombinedP95) { $combinedPeak - $preFaultCombinedP95 } else { $null }) -Denominator $tBacklogNormalization -Missing $missing -Label "backlog drain rows / second ((peak - pre-fault p95) / normalization seconds)"
        faultCohortP99OverConfiguredTimeout = $null
        strandedClaimedRowsOverMaxInFlight = $strandedShare
        recoveryTimeOverPreFaultThroughput = Get-Ratio -Numerator $tBacklogNormalization -Denominator $acceptedRps -Missing $missing -Label "recovery time / pre-fault throughput"
    }
    $faultCohortReading = $faultCohorts["fault-down-arrivals"].reading
    $faultCohortP99 = $null
    if ($null -ne $faultCohortReading) { $faultCohortP99 = Get-Field -Object $faultCohortReading.result -Name @('p99') }
    $claimTimeoutMillis = Get-TimeoutMillis $claimTimeout
    $normalized["faultCohortP99OverConfiguredTimeout"] = Get-Ratio -Numerator $faultCohortP99 -Denominator $claimTimeoutMillis -Missing $missing -Label "fault cohort L_result p99 / configured claim timeout"

    # --- measured-phase average ------------------------------------------------------------------
    # Derived from the two window boundaries the harness recorded, so it is the average over the whole
    # measured phase including the outage and the drain. It is a rate over the phase, not a service
    # rate, and it is labelled that way wherever it is printed.
    $measuredPhaseSeconds = Get-SecondsBetween -From (Get-Field -Object $summary -Name @('events', 'measurementStartedAt')) -To (Get-Field -Object $summary -Name @('events', 'drainEndedAt'))
    if ($null -eq $measuredPhaseSeconds) { $missing.Add("measured phase duration (events.measurementStartedAt to events.drainEndedAt)") }
    $measuredPhaseAcceptedRps = Get-Ratio -Numerator $counts.accepted -Denominator $measuredPhaseSeconds -Missing (New-Object System.Collections.Generic.List[string]) -Label "measured phase accepted RPS"

    # --- operational surface ---------------------------------------------------------------------
    $faultRecoveryParameters = Get-Field -Object $parameters -Name @('faultRecovery')
    $knobCount = $null
    if ($null -ne $faultRecoveryParameters) { $knobCount = @($faultRecoveryParameters.PSObject.Properties).Count }
    $capacity = Get-Field -Object $summary -Name @('capacity')
    $capacityRows = New-Object System.Collections.Generic.List[object]
    if ($null -ne $capacity) {
        foreach ($nodeProperty in $capacity.PSObject.Properties) {
            # The node name is read into a local before the inner metric loop for the same reason as
            # above: inside that loop $_ is the metric, not the node.
            $nodeName = $nodeProperty.Name
            foreach ($metricProperty in $nodeProperty.Value.PSObject.Properties) {
                $capacityRows.Add([ordered]@{
                    node = $nodeName
                    metric = $metricProperty.Name
                    samples = Get-Field -Object $metricProperty.Value -Name @('samples')
                    max = Get-Field -Object $metricProperty.Value -Name @('max')
                    average = Get-Field -Object $metricProperty.Value -Name @('average')
                })
            }
        }
    } else {
        $missing.Add("capacity readings (summary.capacity is absent)")
    }

    $runs.Add([ordered]@{
        runId = $summary.runId
        runDirectory = $runPath
        gitCommit = $summary.gitCommit
        dispatchMode = $summary.dispatchMode
        mysqlMaxInFlightPerNode = $maxInFlight
        mysqlClaimTimeout = $claimTimeout
        mysqlClaimTimeoutMillis = $claimTimeoutMillis
        mysqlClaimBatchSize = $claimBatchSize
        workerCountPerNode = $workerCount
        configurationSource = $configurationSource
        offeredRps = $offeredRps
        samplingIntervalMs = $samplingIntervalMs
        timeBandSeconds = $timeBandSeconds
        backlogBandRows = $backlogBandRows
        killedNode = Get-Field -Object $fault -Name @('source', 'killedNode')
        killedSignal = Get-Field -Object $fault -Name @('source', 'signal')
        downDurationBasis = Get-Field -Object $fault -Name @('source', 'downDurationBasis')
        sampleSource = Get-Field -Object $fault -Name @('source', 'sampleSource')
        sampleCount = Get-Field -Object $fault -Name @('source', 'sampleCount')
        runValidForRecovery = $runValidForRecovery
        recoveryTimeout = $recoveryTimeout
        faultNotInjectedWithActiveWork = Get-Field -Object $fault -Name @('faultNotInjectedWithActiveWork')
        latencyProfile = Get-Field -Object $parameters -Name @('latency')
        accepted = $counts.accepted
        uniqueSubmissions = $counts.uniqueSubmissions
        results = $counts.results
        scoreboardApplied = $counts.scoreboardApplied
        completedHttpRequests = $counts.completedHttpRequests
        countsMissing = $missingCounts.ToArray()
        chainHolds = $chainHolds
        integrityPassed = $integrityPassed
        integrityReason = $integrityReason
        lostOrIncomplete = Get-Field -Object $summary -Name @('integrity', 'lostOrIncomplete')
        finalResultMismatch = Get-Field -Object $summary -Name @('integrity', 'finalResultMismatch')
        countsAvailable = Get-Field -Object $summary -Name @('integrity', 'countsAvailable')
        httpCountsAvailable = ($null -ne $ko429)
        ko429 = $ko429
        ko500 = $ko500
        ko503 = $ko503
        # A run whose integrity failed is not a measurement of anything, so it is kept out of the
        # comparison tables and listed with its reason instead of contributing numbers that would
        # read like results. It is not moved to excluded[]: it was measured, and the reason it does
        # not count is a property of its own numbers rather than a reason it has none.
        excludedFromComparison = (-not $integrityPassed -or $missingCounts.Count -gt 0 -or $null -eq $chainHolds)
        recoveryTimes = [ordered]@{
            T_staleSeconds = $tStale
            T_restartRequestedSeconds = $tRestartRequested
            T_containerRunningSeconds = $tContainerRunning
            T_nodeReadySeconds = $tNodeReady
            T_throughputRecoverySeconds = $tThroughput
            T_backlogNormalizationSeconds = $tBacklogNormalization
            T_judgeBacklogNormalizationSeconds = $tJudgeNormalization
            T_scoreboardBacklogNormalizationSeconds = $tScoreboardNormalization
            T_lastReclaimedResultSeconds = $tLastReclaimedResult
            T_lastReclaimedScoreboardSeconds = $tLastReclaimedScoreboard
            downDurationSeconds = Get-Field -Object $recoveryTimes -Name @('downDurationSeconds')
            downDurationConfiguredSeconds = Get-Field -Object $recoveryTimes -Name @('downDurationConfiguredSeconds')
            drainSeconds = $drainSeconds
            T_staleBasis = Get-Field -Object $recoveryTimes -Name @('T_staleBasis')
            backlogNormalizedDefinition = Get-Field -Object $recovery -Name @('backlogNormalizedDefinition')
        }
        # Whether the two recovery instants were pinned down by samples or by a gap in them. These are
        # three readings of the same searches: where the search started, how wide the holding streak
        # actually was, and whether the ungated search (from faultInjectedAt rather than from
        # max(fault, nodeReady)) landed earlier because the surviving node drained the backlog alone.
        normalizationSearchFromAt = Get-Field -Object $normalization -Name @('searchFromAt')
        normalizationSustainSpanSeconds = Get-Field -Object $normalization -Name @('sustainSpanSeconds')
        normalizationMaxSampleGapSeconds = Get-Field -Object $normalization -Name @('maxSampleGapSeconds')
        normalizationSustainSampleCount = Get-Field -Object $normalization -Name @('sustainSampleCount')
        earliestNormalizedAt = Get-Field -Object $normalization -Name @('earliestNormalizedAt')
        throughputRollingSpanMaxSeconds = Get-Field -Object $throughput -Name @('rollingSpanMaxSeconds')
        anchors = [ordered]@{
            faultInjectedAt = Get-Field -Object $anchors -Name @('faultInjectedAt')
            restartRequestedAt = Get-Field -Object $anchors -Name @('restartRequestedAt')
            containerRunningAt = Get-Field -Object $anchors -Name @('containerRunningAt')
            nodeReadyAt = Get-Field -Object $anchors -Name @('nodeReadyAt')
            firstStaleObservedAt = Get-Field -Object $anchors -Name @('firstStaleObservedAt')
            throughputRecoveredAt = Get-Field -Object $anchors -Name @('throughputRecoveredAt')
            backlogNormalizedAt = Get-Field -Object $anchors -Name @('backlogNormalizedAt')
            lastReclaimedSubmissionResultAt = Get-Field -Object $anchors -Name @('lastReclaimedSubmissionResultAt')
            lastReclaimedSubmissionScoreboardAt = Get-Field -Object $anchors -Name @('lastReclaimedSubmissionScoreboardAt')
        }
        faultCohorts = $faultCohorts
        generalCohorts = $generalCohortReadings
        faultCohortP99ResultMs = $faultCohortP99
        throughput = [ordered]@{
            preFaultResultRps = $preFaultResultRps
            thresholdRps = Get-Field -Object $throughput -Name @('thresholdRps')
            ratio = Get-Field -Object $throughput -Name @('ratio')
            windowSeconds = Get-Field -Object $throughput -Name @('windowSeconds')
            consecutiveWindows = Get-Field -Object $throughput -Name @('consecutiveWindows')
            rollingWindowCount = Get-Field -Object $throughput -Name @('rollingWindowCount')
            secondsAfterNodeReady = Get-Field -Object $throughput -Name @('secondsAfterNodeReady')
            harnessAndAnalyzerAgree = Get-Field -Object $throughput -Name @('harnessAndAnalyzerAgree')
            basis = Get-Field -Object $throughput -Name @('basis')
        }
        preFault = [ordered]@{
            windowFrom = Get-Field -Object $preFault -Name @('windowFrom')
            windowTo = Get-Field -Object $preFault -Name @('windowTo')
            sampleCount = Get-Field -Object $preFault -Name @('sampleCount')
            judgeBacklogP95 = $preFaultJudgeP95
            scoreboardPendingP95 = $preFaultScoreboardP95
            resultRps = Get-Field -Object $preFault -Name @('resultRps')
            basis = Get-Field -Object $preFault -Name @('basis')
        }
        normalizationSection = [ordered]@{
            sustainSeconds = Get-Field -Object $normalization -Name @('sustainSeconds')
            harnessAndAnalyzerAgree = Get-Field -Object $normalization -Name @('harnessAndAnalyzerAgree')
            harnessReportedAt = Get-Field -Object $normalization -Name @('harnessReportedAt')
            basis = Get-Field -Object $normalization -Name @('basis')
        }
        backlogSeries = $backlogSeries
        preFaultCombinedP95 = $preFaultCombinedP95
        measuredPhase = [ordered]@{
            seconds = $measuredPhaseSeconds
            acceptedRps = $measuredPhaseAcceptedRps
            drainSeconds = $drainSeconds
        }
        claimedUnfinishedAtKill = [ordered]@{
            clusterWideUpperBound = $strandedUpperBound
            attributionExact = Get-Field -Object $claimedUnfinished -Name @('attributionExact')
            claimedUnfinishedExact = Get-Field -Object $workCost -Name @('claimedUnfinishedExact')
            ageSecondsAtKill = Get-Field -Object $claimedUnfinished -Name @('ageSecondsAtKill')
            strandedShareOfMaxInFlight = Get-Field -Object $claimedUnfinished -Name @('strandedShareOfMaxInFlight')
            maxInFlightClusterWide = $maxInFlightClusterWide
            basis = Get-Field -Object $claimedUnfinished -Name @('basis')
        }
        workCost = $workCost
        reclaimAccounting = $reclaimAccounting
        trigger = $trigger
        postRecoveryWindow = Get-Field -Object $fault -Name @('postRecoveryWindow')
        capacityScope = Get-Field -Object $summary -Name @('capacityScope')
        capacity = $capacityRows.ToArray()
        faultRecoveryKnobCount = $knobCount
        normalized = $normalized
        unavailableValues = $missing.ToArray()
    })
}

# ---------------------------------------------------------------------------
# Grouping and the direct 4s-versus-10s delta.
# ---------------------------------------------------------------------------
$byMaxInFlight = @{}
foreach ($run in $runs) {
    $groupKey = "mif=$(Format-Value $run.mysqlMaxInFlightPerNode)"
    if (-not $byMaxInFlight.ContainsKey($groupKey)) { $byMaxInFlight[$groupKey] = New-Object System.Collections.Generic.List[object] }
    $byMaxInFlight[$groupKey].Add($run)
}

# The pair the direct comparison is about, found by the configuration the specification names: the
# same max-in-flight per node (64) at the two claim timeouts (4s and 10s). Matching on the parsed
# millisecond value rather than on the literal "4s"/"10s" text means an equivalent spelling such as
# "4000ms" - which the harness writes when the timeout has no exact second form - still finds its run.
function Get-RunByConfiguration {
    param($RunList, $MaxInFlight, $TimeoutMillis)
    foreach ($candidate in $RunList) {
        $candidateMif = ConvertTo-DoubleOrNull $candidate.mysqlMaxInFlightPerNode
        $candidateTimeout = ConvertTo-DoubleOrNull $candidate.mysqlClaimTimeoutMillis
        if ($null -eq $candidateMif -or $null -eq $candidateTimeout) { continue }
        if ($candidateMif -eq $MaxInFlight -and $candidateTimeout -eq $TimeoutMillis) { return $candidate }
    }
    return $null
}

$runFourSeconds = Get-RunByConfiguration -RunList $runs -MaxInFlight 64 -TimeoutMillis 4000
$runTenSeconds = Get-RunByConfiguration -RunList $runs -MaxInFlight 64 -TimeoutMillis 10000
$runSixteen = Get-RunByConfiguration -RunList $runs -MaxInFlight 16 -TimeoutMillis 2500

function New-DeltaRow {
    param([string]$Metric, $ValueA, $ValueB, $Band, [string]$Units, [string]$Verdict)
    $a = ConvertTo-DoubleOrNull $ValueA
    $b = ConvertTo-DoubleOrNull $ValueB
    $delta = $null
    if ($null -ne $a -and $null -ne $b) { $delta = [math]::Round($a - $b, 3) }
    return [ordered]@{
        metric = $Metric
        value4s = $ValueA
        value10s = $ValueB
        delta4sMinus10s = $delta
        samplingBand = $Band
        units = $Units
        verdict = $Verdict
    }
}

$deltaRows = New-Object System.Collections.Generic.List[object]
$deltaNormalizedRows = New-Object System.Collections.Generic.List[object]
$deltaAvailable = ($null -ne $runFourSeconds -and $null -ne $runTenSeconds)
$loadComparable = $false
$loadComparableReason = "one of the two MIF64 runs is not in the comparison at all, so nothing can be compared"
if ($deltaAvailable) {
    $offered4s = ConvertTo-DoubleOrNull $runFourSeconds.offeredRps
    $offered10s = ConvertTo-DoubleOrNull $runTenSeconds.offeredRps
    if ($null -eq $offered4s -or $null -eq $offered10s) {
        $loadComparableReason = "the offered rate is not in one of the two summaries, so the runs cannot be confirmed to have been offered the same load"
    } elseif ($offered4s -eq $offered10s) {
        $loadComparable = $true
        $loadComparableReason = "both runs were offered $offered4s RPS at max-in-flight 64 per node, so a raw difference in a recovery time is a reading about the claim timeout"
    } else {
        $loadComparableReason = "the two runs were offered different rates ($offered4s RPS and $offered10s RPS), so raw RPS and raw latency differences between them are not valid and only the normalized metrics are compared"
    }
}

if ($deltaAvailable) {
    # The sampling band is read from the two runs and the wider of the two is used: the pair is being
    # called against the coarser of its own two measurement resolutions, not the finer one.
    $bandStale = $null
    $bandBacklog = $null
    $bandLatencyBase = $null
    $bandLatencySlow = $null
    $bandSecondsPair = @($runFourSeconds.timeBandSeconds, $runTenSeconds.timeBandSeconds) | Where-Object { $null -ne $_ }
    if ($bandSecondsPair.Count -gt 0) { $bandStale = ($bandSecondsPair | Measure-Object -Maximum).Maximum }
    $bandRowsPair = @($runFourSeconds.backlogBandRows, $runTenSeconds.backlogBandRows) | Where-Object { $null -ne $_ }
    if ($bandRowsPair.Count -gt 0) { $bandBacklog = ($bandRowsPair | Measure-Object -Maximum).Maximum }
    $bandLatencyBase = Get-Field -Object $runFourSeconds.latencyProfile -Name @('baseMillis')
    $bandLatencySlow = Get-Field -Object $runFourSeconds.latencyProfile -Name @('slowMillis')

    $deltaRows.Add((New-DeltaRow -Metric "first stale reclaim time (T_stale)" -ValueA $runFourSeconds.recoveryTimes.T_staleSeconds -ValueB $runTenSeconds.recoveryTimes.T_staleSeconds -Band $bandStale -Units "s" -Verdict (Get-DeltaVerdict -ValueA $runFourSeconds.recoveryTimes.T_staleSeconds -ValueB $runTenSeconds.recoveryTimes.T_staleSeconds -Band $bandStale -Units "s" -LoadComparable $loadComparable)))
    $deltaRows.Add((New-DeltaRow -Metric "backlog peak (judge + scoreboard, recovery-samples.csv max)" -ValueA $runFourSeconds.backlogSeries.combinedPeak -ValueB $runTenSeconds.backlogSeries.combinedPeak -Band $bandBacklog -Units " rows" -Verdict (Get-DeltaVerdict -ValueA $runFourSeconds.backlogSeries.combinedPeak -ValueB $runTenSeconds.backlogSeries.combinedPeak -Band $bandBacklog -Units " rows" -LoadComparable $loadComparable)))
    $deltaRows.Add((New-DeltaRow -Metric "backlog normalization time (combined)" -ValueA $runFourSeconds.recoveryTimes.T_backlogNormalizationSeconds -ValueB $runTenSeconds.recoveryTimes.T_backlogNormalizationSeconds -Band $bandStale -Units "s" -Verdict (Get-DeltaVerdict -ValueA $runFourSeconds.recoveryTimes.T_backlogNormalizationSeconds -ValueB $runTenSeconds.recoveryTimes.T_backlogNormalizationSeconds -Band $bandStale -Units "s" -LoadComparable $loadComparable)))
    $deltaRows.Add((New-DeltaRow -Metric "fault-down cohort L_result p99" -ValueA $runFourSeconds.faultCohortP99ResultMs -ValueB $runTenSeconds.faultCohortP99ResultMs -Band $bandLatencyBase -Units "ms" -Verdict (Get-LatencyDeltaVerdict -ValueA $runFourSeconds.faultCohortP99ResultMs -ValueB $runTenSeconds.faultCohortP99ResultMs -BaseMillis $bandLatencyBase -SlowMillis $bandLatencySlow -LoadComparable $loadComparable)))
    $deltaRows.Add((New-DeltaRow -Metric "throughput recovery time" -ValueA $runFourSeconds.recoveryTimes.T_throughputRecoverySeconds -ValueB $runTenSeconds.recoveryTimes.T_throughputRecoverySeconds -Band $bandStale -Units "s" -Verdict (Get-DeltaVerdict -ValueA $runFourSeconds.recoveryTimes.T_throughputRecoverySeconds -ValueB $runTenSeconds.recoveryTimes.T_throughputRecoverySeconds -Band $bandStale -Units "s" -LoadComparable $loadComparable)))

    $normalizedNames = @(
        @("backlogPeakOverAcceptedRps", "backlog peak / accepted RPS", ""),
        @("recoveredRowsPerSecond", "recovered rows / second", " rows/s"),
        @("faultCohortP99OverConfiguredTimeout", "fault cohort p99 / configured timeout", ""),
        @("strandedClaimedRowsOverMaxInFlight", "stranded claimed rows / max-in-flight", ""),
        @("recoveryTimeOverPreFaultThroughput", "recovery time / pre-fault throughput", " s per row/s")
    )
    foreach ($normalizedName in $normalizedNames) {
        # The three columns are read into locals first: the inner index into the run's own hashtable
        # would otherwise be reached through $_ and the outer run object is not what $_ refers to.
        $metricKey = $normalizedName[0]
        $metricLabel = $normalizedName[1]
        $metricUnits = $normalizedName[2]
        $value4s = Get-Field -Object $runFourSeconds.normalized -Name @($metricKey)
        $value10s = Get-Field -Object $runTenSeconds.normalized -Name @($metricKey)
        $valueA = ConvertTo-DoubleOrNull $value4s
        $valueB = ConvertTo-DoubleOrNull $value10s
        $delta = $null
        if ($null -ne $valueA -and $null -ne $valueB) { $delta = [math]::Round($valueA - $valueB, 6) }
        # A normalized metric is a ratio of two measured quantities, so its own sampling band is the
        # metric's band divided by the same denominator. That is not recomputed here; what is said
        # instead is which side is lower and whether the raw pair it came from was comparable at all.
        $verdict = "not comparable: the two runs were offered different loads"
        if ($loadComparable) {
            if ($null -eq $delta) {
                $verdict = "unavailable: one of the two runs did not carry this ratio (its numerator or denominator is missing from the summary)"
            } elseif ($delta -eq 0) {
                $verdict = "identical on this measurement"
            } else {
                $verdict = "4s is lower by $(Format-Number ([math]::Abs($delta)))$metricUnits on this ratio; the ratio's own sampling band is not carried in the summary, so this is a direction and not a significance call, and lower is not the better side of all five ratios - read the direction against the definition above"
            }
        }
        $deltaNormalizedRows.Add([ordered]@{
            metric = $metricLabel
            value4s = $value4s
            value10s = $value10s
            delta4sMinus10s = $delta
            units = $metricUnits
            verdict = $verdict
        })
    }
}

# ---------------------------------------------------------------------------
# Output paths.
#
# Resolving the output path would fail on a directory that does not exist yet, which made the one
# natural way to run this - point it at a fresh folder - an error, so the directory is created first.
# ---------------------------------------------------------------------------
$outputRoot = ""
if ($OutputDirectory) {
    if (-not (Test-Path $OutputDirectory)) { New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null }
    $outputRoot = (Resolve-Path $OutputDirectory).Path
} else {
    $defaultOutput = Join-Path (Join-Path $PSScriptRoot "..\..") "results\mysql-judge-tradeoff"
    if (-not (Test-Path $defaultOutput)) { New-Item -ItemType Directory -Force -Path $defaultOutput | Out-Null }
    $outputRoot = (Resolve-Path $defaultOutput).Path
}
$comparisonPath = Join-Path $outputRoot "$OutputBaseName.json"
$markdownPath = Join-Path $outputRoot "$OutputBaseName.md"

# ---------------------------------------------------------------------------
# The interpretation rules the limits section states, collected once so the JSON and the Markdown
# carry the same text. These are rules about how to read the numbers, not measured values, and each
# one is repeated from the run's own unavailable[] list when the summary carries it.
# ---------------------------------------------------------------------------
$limitStatements = @(
    "The SIGKILLed JVM's in-process counters (judgeInvocations and everything derived from it) are lower bounds, not totals: the process was killed, so every invocation, duration and claim increment recorded between the last scrape and the kill died with it. A lost counter is not a zero, and this comparison never reads one as a zero.",
    "attempts > 1 counts recovery re-claims after the static lease expired. It must NOT be called concurrent duplicate CPU execution: the process holding the claim was SIGKILLed, so it did not keep judging while the row was handed out again.",
    "Testing true concurrent duplicate execution and fencing needs a separate docker pause -> wait past the claim timeout -> unpause experiment, which this round does not run."
)

# Every distinct unavailable[] entry across the runs, deduplicated by text: the same reason is
# written into each run's summary, and repeating it per run would bury the ones that differ.
$limitEntries = New-Object System.Collections.Generic.List[object]
$seenLimitText = @{}
foreach ($run in $runs) {
    $summary = Get-Content (Join-Path $run.runDirectory "summary.json") -Raw | ConvertFrom-Json
    # The entry's origin is read into a local before the inner loop, so the run id it is attributed
    # to is this run's and not whatever $_ happens to be inside the loop below.
    $originRunId = $run.runId
    # Built as objects rather than as nested @() pairs: a nested array inside an array literal is
    # flattened by PowerShell, so $source[1] would be the first element of the entry list rather than
    # the list itself.
    $unavailableSources = New-Object System.Collections.Generic.List[object]
    $unavailableSources.Add([pscustomobject]@{ name = "summary.unavailable"; entries = (Get-Field -Object $summary -Name @('unavailable')) })
    $unavailableSources.Add([pscustomobject]@{ name = "faultRecovery.unavailable"; entries = (Get-Field -Object $summary -Name @('faultRecovery', 'unavailable')) })
    $unavailableSources.Add([pscustomobject]@{ name = "staircase.unavailable"; entries = (Get-Field -Object $summary -Name @('staircase', 'unavailable')) })
    $unavailableSources.Add([pscustomobject]@{ name = "capacity.unavailable"; entries = (Get-Field -Object $summary -Name @('capacity', 'unavailable')) })
    foreach ($source in $unavailableSources.ToArray()) {
        $sourceName = $source.name
        foreach ($entry in @($source.entries)) {
            if ($null -eq $entry) { continue }
            $text = [string]$entry
            if ([string]::IsNullOrWhiteSpace($text)) { continue }
            if ($seenLimitText.ContainsKey($text)) { continue }
            $seenLimitText[$text] = $true
            $limitEntries.Add([ordered]@{ source = $sourceName; runId = $originRunId; text = $text })
        }
    }
}

# The per-run list of values the summary did not carry, flattened across runs. It is written to the
# JSON because it is the answer to "which numbers in this report are absent rather than zero".
$unavailableValues = New-Object System.Collections.Generic.List[object]
foreach ($run in $runs) {
    $runId = $run.runId
    $runDirectoryPath = $run.runDirectory
    foreach ($value in @($run.unavailableValues)) {
        $unavailableValues.Add([ordered]@{ runId = $runId; runDirectory = $runDirectoryPath; value = $value })
    }
}

# The MIF16 run's own block, mirrored from the Markdown section so a consumer of the JSON does not have
# to search runs[] by hand for it. Assigned inside the branch rather than through an if-expression:
# an if statement in an expression position unrolls an [ordered]@{} into its entries in PowerShell 5.1.
$mif16RunJson = $null
if ($null -ne $runSixteen) {
    $mif16RunJson = [ordered]@{
        runId = $runSixteen.runId
        mysqlMaxInFlightPerNode = $runSixteen.mysqlMaxInFlightPerNode
        mysqlClaimTimeout = $runSixteen.mysqlClaimTimeout
        offeredRps = $runSixteen.offeredRps
        accepted = $runSixteen.accepted
        uniqueSubmissions = $runSixteen.uniqueSubmissions
        results = $runSixteen.results
        scoreboardApplied = $runSixteen.scoreboardApplied
        chainHolds = $runSixteen.chainHolds
        preFaultResultRps = $runSixteen.throughput.preFaultResultRps
        firstStaleReclaimSeconds = $runSixteen.recoveryTimes.T_staleSeconds
        backlogNormalizationSeconds = $runSixteen.recoveryTimes.T_backlogNormalizationSeconds
        throughputRecoverySeconds = $runSixteen.recoveryTimes.T_throughputRecoverySeconds
        judgeBacklogPeak = $(if ($null -ne $runSixteen.backlogSeries) { $runSixteen.backlogSeries.judgeBacklogPeak } else { $null })
        scoreboardPendingPeak = $(if ($null -ne $runSixteen.backlogSeries) { $runSixteen.backlogSeries.scoreboardPendingPeak } else { $null })
        clusterWideClaimedUnfinishedUpperBound = $runSixteen.claimedUnfinishedAtKill.clusterWideUpperBound
        reclaimedRowsAfterFault = Get-Field -Object $runSixteen.reclaimAccounting -Name @('reclaimedRowsAfterFault')
        drainSeconds = $runSixteen.recoveryTimes.drainSeconds
        normalized = $runSixteen.normalized
        comparisonNote = "this run was offered a different load from the MIF64 runs, so only its normalized ratios may be read beside them"
    }
}

$comparison = [ordered]@{
    generatedAt = [datetimeoffset]::UtcNow.ToString("o")
    outputBaseName = $OutputBaseName
    sectionOrder = [object[]]$sectionOrder
    runOrder = [object[]]@($runs | ForEach-Object { $_.runId })
    runDirectories = [object[]]@($runs | ForEach-Object { $_.runDirectory })
    runs = [object[]]$runs
    groups = [ordered]@{}
    delta = [ordered]@{
        configuration = "max-in-flight 64 per node, claim timeout 4s versus 10s"
        run4s = $(if ($null -ne $runFourSeconds) { $runFourSeconds.runId } else { $null })
        run10s = $(if ($null -ne $runTenSeconds) { $runTenSeconds.runId } else { $null })
        deltaDefinition = "delta is the 4s run's value minus the 10s run's value, so a negative delta means 4s was lower or faster"
        loadComparable = $loadComparable
        loadComparableReason = $loadComparableReason
        metrics = [object[]]$deltaRows
        normalizedMetrics = [object[]]$deltaNormalizedRows
    }
    mif16RunId = $(if ($null -ne $runSixteen) { $runSixteen.runId } else { $null })
    mif16Run = $mif16RunJson
    normalizedMetricsByRun = [ordered]@{}
    excluded = [object[]]$excluded
    unavailableValues = [object[]]$unavailableValues
    limitStatements = [object[]]$limitStatements
    limitEntries = [object[]]$limitEntries
}
foreach ($groupKey in @($byMaxInFlight.Keys | Sort-Object)) {
    # [object[]], not @(): PowerShell 5.1 refuses @() around the List[object] a group is built in,
    # and the failure reads only as "Argument types do not match".
    $comparison.groups[$groupKey] = [object[]]$byMaxInFlight[$groupKey]
}
foreach ($run in $runs) {
    $comparison.normalizedMetricsByRun[$run.runId] = $run.normalized
}
$comparison | ConvertTo-Json -Depth 12 | Set-Content $comparisonPath -Encoding utf8

# ---------------------------------------------------------------------------
# Markdown.
#
# The report is written to be self-contained: every number is in the text, and a reader who never
# opens the JSON still gets each value, its definition and its band. A p50/p95/p99/max quartet is one
# cell so a missing branch reads as "unavailable" in the same place a value would have been.
# ---------------------------------------------------------------------------
function Format-PercentileQuad {
    param($Block)
    if ($null -eq $Block) { return "unavailable" }
    return "$(Format-Number (Get-Field -Object $Block -Name @('p50')))/$(Format-Number (Get-Field -Object $Block -Name @('p95')))/$(Format-Number (Get-Field -Object $Block -Name @('p99')))/$(Format-Number (Get-Field -Object $Block -Name @('max')))"
}

function Get-CohortRow {
    param($Run, [string]$CohortName, [string]$Letter, $CohortEntry)
    if ($null -eq $CohortEntry -or $null -eq $CohortEntry.reading) {
        $reason = "unavailable"
        if ($null -ne $CohortEntry) { $reason = "unavailable: $($CohortEntry.unavailableReason)" }
        return "| $($Run.runId) | $Letter $CohortName | $reason | unavailable | unavailable | unavailable | unavailable | unavailable |"
    }
    $reading = $CohortEntry.reading
    return "| $($Run.runId) | $Letter $CohortName | $(Format-Value $reading.sampleCount) | " +
        "$(Format-PercentileQuad $reading.total) | $(Format-PercentileQuad $reading.result) | $(Format-PercentileQuad $reading.scoreboard) | " +
        "$(Format-RatioPercent $reading.over5sRatio) | $(Format-RatioPercent $reading.over10sRatio) |"
}

$lines = New-Object System.Collections.Generic.List[string]

$lines.Add("# MySQL judge fault recovery comparison")
$lines.Add("")
$lines.Add("- Runs in comparison order: $((@($runs | ForEach-Object { $_.runId })) -join ' -> ')")
$lines.Add("- Generated at: $($comparison.generatedAt)")
$lines.Add("- Excluded: $(if ($excluded.Count -eq 0) { 'none' } else { (@($excluded | ForEach-Object { "$($_.runDirectory): $($_.reason)" })) -join '; ' })")
$lines.Add("- Section order, fixed by the experiment specification: $(for ($i = 0; $i -lt $sectionOrder.Count; $i++) { "$($i + 1). $($sectionOrder[$i])" }) ")
$lines.Add("")
$lines.Add("Raw observations only; no claim timeout is recommended here. Every run in this comparison has one node SIGKILLed inside its measured window and restarted, so no window in any of them is service time: the percentiles below are queueing and recovery latency, and the fault-recovery cohorts are what this run measured instead of a steady state. The sections are in the specification's fixed order because the order is the argument - correctness first, because a run whose row counts do not balance has no timing worth comparing, then how long the backlog took to recover, then what the reclaimed work cost the submissions that waited for it, then the throughput, then the peak, then the extra claim and DB work, then what an operator has to reason about.")
$lines.Add("")
$lines.Add("`unavailable` in any cell means the value is not in that run's summary.json and nothing here was computed in its place; the exact paths that were looked for are listed in the `## Values the summaries did not carry` section at the end, and in the JSON under `unavailableValues`.")
$lines.Add("")

# --- inventory -------------------------------------------------------------------------------------
$lines.Add("## Run inventory")
$lines.Add("")
$lines.Add("| # | run id | dispatch | MIF/node | claim timeout | batch | workers/node | offered RPS | killed node | down s (configured) | integrity | valid for recovery | config read from |")
$lines.Add("|---:|---|---|---:|---|---:|---:|---|---|---:|---|---|---|")
$inventoryIndex = 0
foreach ($run in $runs) {
    $inventoryIndex++
    $downConfigured = Get-Field -Object $run.recoveryTimes -Name @('downDurationConfiguredSeconds')
    $validForRecovery = Get-Field -Object $run -Name @('runValidForRecovery')
    $lines.Add("| $inventoryIndex | $($run.runId) | $(Format-Value $run.dispatchMode) | $(Format-Value $run.mysqlMaxInFlightPerNode) | $(Format-Value $run.mysqlClaimTimeout) | " +
        "$(Format-Value $run.mysqlClaimBatchSize) | $(Format-Value $run.workerCountPerNode) | $(Format-Value $run.offeredRps) | $(Format-Value $run.killedNode) | $(Format-Value $downConfigured) | " +
        "$(if ($run.integrityPassed) { 'passed' } else { 'FAILED' }) | $(Format-Value $validForRecovery) | $($run.configurationSource) |")
}
$lines.Add("")
$lines.Add("The comparison order above is the order this file was given the run directories in. Max-in-flight and the claim timeout are read from `summary.staircase` first and from `parameters.json` only when the summary does not carry them; the last column says which one answered, because on a regenerated analysis the two can disagree and the reader needs to know which number is being discussed.")
$lines.Add("")

# --- excluded --------------------------------------------------------------------------------------
$lines.Add("## Excluded runs")
$lines.Add("")
if ($excluded.Count -eq 0) {
    # Single-quoted on purpose: this paragraph carries Markdown code spans, and a backtick followed
    # by a, b, f, n, r, t or v is a PowerShell escape inside a double-quoted string, so "`f..." would
    # silently become a form feed and the code span would vanish from the report.
    $lines.Add('None. Every run directory given to this comparer carried a summary.json, a true `faultRecovery.runValidForRecovery` and a false `recoveryTimeout`, so every one of them is reported below as a measured recovery.')
} else {
    $lines.Add("These directories are not reported as successes anywhere in this file. A run that failed, a run that was never analyzed, a run whose fault was not injected under observed work and a run that hit the harness's recovery deadline are four different situations, and each is stated rather than folded into one.")
    $lines.Add("")
    $lines.Add("| run directory | run id | reason |")
    $lines.Add("|---|---|---|")
    foreach ($entry in $excluded.ToArray()) {
        $lines.Add("| $($entry.runDirectory) | $(Format-Value $entry.runId) | $($entry.reason) |")
    }
}
$lines.Add("")

# --- 1. correctness / integrity --------------------------------------------------------------------
$lines.Add("## 1. Correctness and integrity")
$lines.Add("")
$lines.Add('The chain is `accepted == uniqueSubmissions == results == scoreboardApplied`: every accepted submission is a distinct submission, every one of them produced a result row, and every result row was applied to the scoreboard. It is evaluated as one comparison rather than four counts so that three cannot be checked while the fourth is missed, and it is reported before any timing because a run that fails it has no timing worth reading.')
$lines.Add("")
$lines.Add("| run id | accepted | uniqueSubmissions | results | scoreboardApplied | completed HTTP requests | chain holds | lostOrIncomplete | finalResultMismatch | integrity.passed | 429 | 500 | 503 |")
$lines.Add("|---|---:|---:|---:|---:|---:|---|---:|---:|---|---:|---:|---:|")
foreach ($run in $runs) {
    $chainCell = Format-Value $run.chainHolds
    if ($null -eq $run.chainHolds) { $chainCell = "not evaluable ($(@($run.countsMissing) -join ', ') missing)" }
    $httpCell = $(if ($run.httpCountsAvailable) { "" } else { "unavailable" })
    $lines.Add("| $($run.runId) | $(Format-Value $run.accepted) | $(Format-Value $run.uniqueSubmissions) | $(Format-Value $run.results) | $(Format-Value $run.scoreboardApplied) | " +
        "$(Format-Value $run.completedHttpRequests) | $chainCell | $(Format-Value $run.lostOrIncomplete) | $(Format-Value $run.finalResultMismatch) | $(Format-Value $run.integrityPassed) | " +
        "$(if ($run.httpCountsAvailable) { Format-Value $run.ko429 } else { $httpCell }) | " +
        "$(if ($run.httpCountsAvailable) { Format-Value $run.ko500 } else { $httpCell }) | " +
        "$(if ($run.httpCountsAvailable) { Format-Value $run.ko503 } else { $httpCell }) |")
}
$lines.Add("")
$httpUnavailableRuns = @($runs | Where-Object { -not $_.httpCountsAvailable })
if ($httpUnavailableRuns.Count -gt 0) {
    $lines.Add("The HTTP 429/500/503 columns are unavailable for $(@($httpUnavailableRuns | ForEach-Object { $_.runId }) -join ', '). A fault-recovery run offers a single rate, so its summary has no `staircase.stages[].http` block to sum: the per-stage HTTP outcome table is written by the staircase path, and this experiment does not take it. Nothing was read from a Gatling log to fill the gap, because this comparer was not told which log to read; the runs' own `staircase.unavailable[]` entries state the reason and are reproduced under `## Limits`.")
}
$integrityFailedRuns = @($runs | Where-Object { $_.excludedFromComparison })
if ($integrityFailedRuns.Count -gt 0) {
    $lines.Add("")
    $lines.Add("Runs whose integrity check did not confirm the chain are listed here with their reason and are held out of the comparison tables below, because their counts cannot establish that the work they timed was complete: $(@($integrityFailedRuns | ForEach-Object { "$($_.runId) - $($_.integrityReason)" }) -join '; ').")
}
$lines.Add("")

# --- 2. backlog recovery time ----------------------------------------------------------------------
$lines.Add("## 2. Backlog recovery time")
$lines.Add("")
$lines.Add('Each row is one definition of "how long the recovery took", measured from `faultInjectedAt` unless the definition says otherwise. They are reported side by side because they answer different questions and the spread between them is the interesting part: the lease-expiry instant is not the same as the node being back, and the node being back is not the same as the queue being empty. None of these is an assumed value - `T_stale` in particular is NOT assumed to equal the configured claim timeout, because the lease expires at `claimed_at + timeout` and the claim was already older than that at the kill instant.')
$lines.Add("")
$lines.Add("| run id | T_stale (first stale reclaim) | T_restart_requested | T_container_running | T_node_ready | throughput recovery | backlog normalization (judge) | backlog normalization (scoreboard) | backlog normalization (combined) | T_last_reclaimed_result | T_last_reclaimed_scoreboard | drain |")
$lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($run in $runs) {
    $recoveryTimeCells = $run.recoveryTimes
    $lines.Add("| $($run.runId) | $(Format-Seconds $recoveryTimeCells.T_staleSeconds) | $(Format-Seconds $recoveryTimeCells.T_restartRequestedSeconds) | " +
        "$(Format-Seconds $recoveryTimeCells.T_containerRunningSeconds) | $(Format-Seconds $recoveryTimeCells.T_nodeReadySeconds) | $(Format-Seconds $recoveryTimeCells.T_throughputRecoverySeconds) | " +
        "$(Format-Seconds $recoveryTimeCells.T_judgeBacklogNormalizationSeconds) | $(Format-Seconds $recoveryTimeCells.T_scoreboardBacklogNormalizationSeconds) | " +
        "$(Format-Seconds $recoveryTimeCells.T_backlogNormalizationSeconds) | $(Format-Seconds $recoveryTimeCells.T_lastReclaimedResultSeconds) | " +
        "$(Format-Seconds $recoveryTimeCells.T_lastReclaimedScoreboardSeconds) | $(Format-Seconds $recoveryTimeCells.drainSeconds) |")
}
$lines.Add("")
$lines.Add("`T_stale` is read from the durable `SUM(attempts - 1)` crossing its pre-fault value, polled about once a second, so it is bounded below by the poll interval and is not the instant the lease was actually acquired. `T_container_running` is `docker inspect` reporting `State.Running`, which is not readiness: `T_node_ready` is the first instant the container was running AND the readiness endpoint was UP AND Prometheus was scrapable AND the claim counter was observed to advance. The two backlog-normalization columns are the judge backlog and the scoreboard pending backlog each reaching and holding at or below their own pre-fault p95; the combined column is the later of the two and is the one the specification calls the backlog normalization time. `T_last_reclaimed_*` is the last reclaimed submission's result row and scoreboard application, which is when the customers of the re-claimed work finally saw it. Drain is the harness's own loop reaching a zero backlog, and it is measured after the load stops, so it is not comparable with the columns before it.")
foreach ($run in $runs) {
    $staleBasis = Get-Field -Object $run.recoveryTimes -Name @('T_staleBasis')
    if ($null -ne $staleBasis) {
        $lines.Add("")
        $lines.Add("- $($run.runId) T_stale basis: $staleBasis")
    }
}
$lines.Add("")
$lines.Add('Two readings that say whether the two recovery instants above were pinned down by samples or by a gap in them. `backlog norm search from` is the instant both backlog searches started at, and `earliest` is the same search run from `faultInjectedAt` instead: when it lands earlier, the surviving node drained the backlog before the killed one was serving again, and the combined column above deliberately does not use it. `judge sustain span s` is how much wall clock the accepted hold actually covered and `widest gap s` is the widest gap between two samples inside it, so a hold confirmed by six consecutive 1s samples is told apart from one confirmed by three samples across a 2.5s gap: the second is still a hold under the documented 2.5s limit, but it rests on fewer observations and now says so. `rolling span max` is the widest 5s throughput window that survived the span bound, so it shows how much of the rolling rate rests on a sampling gap rather than on five consecutive seconds.')
$lines.Add("")
$lines.Add("| run id | backlog norm search from | judge sustain span s | widest gap s | hold samples | earliest normalization (ungated) | rolling span max s | rolling windows |")
$lines.Add("|---|---|---:|---:|---:|---|---:|---:|")
foreach ($run in $runs) {
    $throughputBlock = Get-Field -Object $run.summary -Name @('faultRecovery', 'throughput')
    $rowsCell = Format-Value (Get-Field -Object $throughputBlock -Name @('rollingWindowCount'))
    $lines.Add("| $($run.runId) | $(Format-Value $run.normalizationSearchFromAt) | $(Format-Value $run.normalizationSustainSpanSeconds) | $(Format-Value $run.normalizationMaxSampleGapSeconds) | $(Format-Value $run.normalizationSustainSampleCount) | $(Format-Value $run.earliestNormalizedAt) | $(Format-Value $run.throughputRollingSpanMaxSeconds) | $rowsCell |")
}
$lines.Add("")

# --- 3. reclaimed cohort ----------------------------------------------------------------------------
$lines.Add("## 3. Reclaimed cohort L_total p95/p99/max")
$lines.Add("")
$lines.Add('The `reclaimed-after-fault` cohort is the submissions whose outbox row was handed out again after the lease expired - the work the outage stranded. Their `L_total` is the interval from submission to scoreboard application, so it includes the entire outage, which is why its percentiles sit near the outage length rather than near a service time. A `fast` class exists only when a submission in this cohort happened to be a fast one: the cohort is selected by the outbox row being re-claimed, not by latency, so on a run where every stranded submission was a slow one the fast half is empty and is reported as such.')
$lines.Add("")
$lines.Add("| run id | L_total p50/p95/p99/max ms | L_result p50/p95/p99/max ms | L_scoreboard p50/p95/p99/max ms | submissions | over 5s | over 10s |")
$lines.Add("|---|---|---|---|---:|---:|---:|")
foreach ($run in $runs) {
    $entry = $run.faultCohorts["reclaimed-after-fault"]
    if ($null -eq $entry -or $null -eq $entry.reading) {
        $reasonCell = "unavailable"
        if ($null -ne $entry) { $reasonCell = "unavailable: $($entry.unavailableReason)" }
        $lines.Add("| $($run.runId) | $reasonCell |  |  |  |  |  |")
        continue
    }
    $reading = $entry.reading
    $lines.Add("| $($run.runId) | $(Format-PercentileQuad $reading.total) | $(Format-PercentileQuad $reading.result) | $(Format-PercentileQuad $reading.scoreboard) | " +
        "$(Format-Value $reading.sampleCount) | $(Format-RatioPercent $reading.over5sRatio) | $(Format-RatioPercent $reading.over10sRatio) |")
}
$lines.Add("")

# --- 4. fault/down cohort fast class ----------------------------------------------------------------
$lines.Add("## 4. Fault/down cohort fast p95/p99")
$lines.Add("")
$lines.Add('The `fault-down-arrivals` cohort is the submissions that arrived between the kill and the node confirming an active dispatcher, split by the deterministic latency class of the submission itself. The `fast` half is the sharpest reading in this comparison: those submissions needed the base service time and nothing more, so any excess over that base is the outage and the recovery, not judge work. The `slow` half is reported beside it for completeness and carries its own 2000ms tail, which makes it far less sensitive to the outage.')
$lines.Add("")
$lines.Add("| run id | class | L_total p50/p95/p99/max ms | L_result p50/p95/p99/max ms | L_scoreboard p50/p95/p99/max ms | submissions |")
$lines.Add("|---|---|---|---|---:|---:|")
foreach ($run in $runs) {
    $entry = $run.faultCohorts["fault-down-arrivals"]
    if ($null -eq $entry -or $null -eq $entry.reading) {
        $reasonCell = "unavailable"
        if ($null -ne $entry) { $reasonCell = "unavailable: $($entry.unavailableReason)" }
        $lines.Add("| $($run.runId) | fast and slow | $reasonCell |  |  |  |")
        continue
    }
    $reading = $entry.reading
    foreach ($latencyClass in @("fast", "slow")) {
        $classBlock = $(if ($latencyClass -eq "fast") { $reading.fast } else { $reading.slow })
        if ($null -eq $classBlock) {
            $lines.Add("| $($run.runId) | $latencyClass | not in this summary |  |  |  |")
            continue
        }
        $lines.Add("| $($run.runId) | $latencyClass | $(Format-PercentileQuad $classBlock.L_total_ms) | $(Format-PercentileQuad $classBlock.L_result_ms) | " +
            "$(Format-PercentileQuad $classBlock.L_scoreboard_ms) | $(Format-Value $classBlock.submissionCount) |")
    }
    $lines.Add("| $($run.runId) | whole cohort | $(Format-PercentileQuad $reading.total) | $(Format-PercentileQuad $reading.result) | $(Format-PercentileQuad $reading.scoreboard) | $(Format-Value $reading.sampleCount) |")
}
$lines.Add("")
$lines.Add("The p99 is the column to compare across runs, and only against the configured claim timeout of the same run: the cohort is small (tens of submissions), so its p99 is the largest one or two observations and can move by a whole latency class between runs at the same configuration. It is step-valued, not continuous.")
$lines.Add("")

# --- 5. throughput recovery time -------------------------------------------------------------------
$lines.Add("## 5. Throughput recovery time")
$lines.Add("")
$lines.Add('Throughput recovery is defined by a rule, not by an eyeball: the first 5-second rolling result-RPS window at or after the node-ready instant whose value and the next two consecutive windows are all at or above 90% of the pre-fault result RPS. Three consecutive windows are required so that one lucky window during the restart does not count as recovered, and the pre-fault baseline is the result RPS over the 30s to 5s before the kill, so arrivals that landed while the fault was being injected are excluded from it.')
$lines.Add("")
$lines.Add("| run id | pre-fault result RPS | threshold (90%) RPS | window s | consecutive windows | rolling windows | recovery after fault | recovery after node ready | harness and analyzer agree |")
$lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---|")
foreach ($run in $runs) {
    $throughputReading = $run.throughput
    $lines.Add("| $($run.runId) | $(Format-Value $throughputReading.preFaultResultRps) | $(Format-Value $throughputReading.thresholdRps) | " +
        "$(Format-Value $throughputReading.windowSeconds) | $(Format-Value $throughputReading.consecutiveWindows) | $(Format-Value $throughputReading.rollingWindowCount) | " +
        "$(Format-Seconds $run.recoveryTimes.T_throughputRecoverySeconds) | $(Format-Seconds $throughputReading.secondsAfterNodeReady) | $(Format-Value $throughputReading.harnessAndAnalyzerAgree) |")
}
$lines.Add("")
$lines.Add('The recovery time above is measured from `faultInjectedAt`, so it contains the whole down window; the after-node-ready column is the same instant measured from the node becoming ready and is the part of the recovery the restart gate did not already explain. When the agreement flag is no, the harness-reported instant and the analyzer-derived one disagree, and the analyzer value is the one tabulated.')
$lines.Add("")

# --- 6. backlog peak and the time series -----------------------------------------------------------
$lines.Add("## 6. Backlog peak and time series summary")
$lines.Add("")
$lines.Add('The peak is the largest backlog either queue reached after the kill, and it is not in summary.json: the summary carries the pre-fault percentiles the normalisation rule compares against and the instant normalisation completed, but not the maximum in between. It is read from `recovery-samples.csv` in the run directory, as the maximum of the `judgeBacklog` column and separately of the `scoreboardPending` column. That is the only file outside summary.json this comparer reads, and it is read for this one quantity. Where the file is absent the peak is `unavailable` and was NOT reconstructed from a throughput series.')
$lines.Add("")
$lines.Add("| run id | judge backlog pre-fault p95 | scoreboard pending pre-fault p95 | combined pre-fault p95 | judge backlog peak | scoreboard pending peak | combined peak | peak / pre-fault p95 (combined) | peak judge backlog growth rows/s | peak scoreboard growth rows/s | samples |")
$lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($run in $runs) {
    $preFaultJudge = Get-Field -Object $run.preFault -Name @('judgeBacklogP95')
    $preFaultScoreboard = Get-Field -Object $run.preFault -Name @('scoreboardPendingP95')
    $series = $run.backlogSeries
    $peakMultiple = $null
    if ($null -ne $series -and $null -ne $series.combinedPeak -and $null -ne $run.preFaultCombinedP95 -and $run.preFaultCombinedP95 -ne 0) {
        $peakMultiple = [math]::Round($series.combinedPeak / $run.preFaultCombinedP95, 3)
    }
    $judgePeakCell = "unavailable"
    $scoreboardPeakCell = "unavailable"
    $combinedPeakCell = "unavailable"
    $judgeGrowthCell = "unavailable"
    $scoreboardGrowthCell = "unavailable"
    $seriesSampleCell = "unavailable"
    if ($null -ne $series) {
        $judgePeakCell = Format-Value $series.judgeBacklogPeak
        $scoreboardPeakCell = Format-Value $series.scoreboardPendingPeak
        $combinedPeakCell = Format-Value $series.combinedPeak
        $judgeGrowthCell = Format-Number $series.judgeBacklogPeakGrowthRowsPerSec
        $scoreboardGrowthCell = Format-Number $series.scoreboardPendingPeakGrowthRowsPerSec
        $seriesSampleCell = Format-Value $series.sampleCount
    }
    $lines.Add("| $($run.runId) | $(Format-Value $preFaultJudge) | $(Format-Value $preFaultScoreboard) | $(Format-Value $run.preFaultCombinedP95) | " +
        "$judgePeakCell | $scoreboardPeakCell | $combinedPeakCell | $(Format-Number $peakMultiple) | $judgeGrowthCell | $scoreboardGrowthCell | $seriesSampleCell |")
}
$lines.Add("")
$lines.Add("The two growth columns are the largest rise between two consecutive samples of that queue, in rows per second: the steepest part of the outage, which is what a reader asking how fast this went wrong wants, and a property of the sampled series rather than of the run as a whole. The peak-over-pre-fault-p95 multiple is what makes two runs at different offered rates comparable at all, because the baseline is each run's own.")
$lines.Add("")
$lines.Add("### Throughput time series, as far as the summary carries it")
$lines.Add("")
$lines.Add('Only the summary own fields are used here: the pre-fault result RPS from `faultRecovery.preFault.resultRps`, the recovery rule parameters and result from `faultRecovery.throughput`, and the window boundaries from `events`. No rolling RPS is recomputed from any file - the one file this comparer opens outside summary.json is `recovery-samples.csv`, and it is used for the backlog columns above only. A run-average accepted rate is given because two fields that are both present make it derivable, and it is labelled as an average over the whole measured phase rather than as a rate.')
$lines.Add("")
$lines.Add("| run id | pre-fault result RPS | measured phase s | accepted in the phase | phase-average accepted RPS | results | scoreboard applied | drain s | backlog normalized at |")
$lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---|")
foreach ($run in $runs) {
    $phase = $run.measuredPhase
    $normalizedAt = Get-Field -Object $run.anchors -Name @('backlogNormalizedAt', 'value')
    $lines.Add("| $($run.runId) | $(Format-Value $run.throughput.preFaultResultRps) | $(Format-Value $phase.seconds) | $(Format-Value $run.accepted) | " +
        "$(Format-Number $phase.acceptedRps) | $(Format-Value $run.results) | $(Format-Value $run.scoreboardApplied) | $(Format-Value $phase.drainSeconds) | $(Format-Value $normalizedAt) |")
}
$lines.Add("")
$lines.Add("The phase-average accepted RPS spans the pre-fault steady state, the outage and the drain together, so it is below the pre-fault rate by construction and is not a capacity reading. A drop-and-recover series would need per-second result RPS, which this summary does not carry; that absence is accepted here rather than filled in from another file.")
$lines.Add("")

# --- 7. unnecessary claim and DB cost --------------------------------------------------------------
$lines.Add("## 7. Unnecessary claim and DB cost")
$lines.Add("")
$lines.Add("First the state at the kill instant, because it is what the extra claim work is proportional to. Both nodes' gauges are reported: the trigger decided to kill on a condition over both of them, so the state of the node that was NOT killed is part of the record of why the fault was injected when it was.")
$lines.Add("")
$lines.Add("| run id | trigger window opened | trigger observed | waited s | escalation | primary condition | fallback condition | judge-1 running/reserved/queued | judge-2 running/reserved/queued | active work seen |")
$lines.Add("|---|---|---|---:|---|---|---|---|---|---|")
foreach ($run in $runs) {
    $triggerBlock = $run.trigger
    $judge1 = Get-Field -Object $triggerBlock -Name @('judge1')
    $judge2 = Get-Field -Object $triggerBlock -Name @('judge2')
    $lines.Add("| $($run.runId) | $(Format-Value (Get-Field -Object $triggerBlock -Name @('windowOpenedAt'))) | $(Format-Value (Get-Field -Object $triggerBlock -Name @('observedAt'))) | " +
        "$(Format-Value (Get-Field -Object $triggerBlock -Name @('waitedSeconds'))) | $(Format-Value (Get-Field -Object $triggerBlock -Name @('escalation'))) | " +
        "$(Format-Value (Get-Field -Object $triggerBlock -Name @('primaryCondition'))) | $(Format-Value (Get-Field -Object $triggerBlock -Name @('fallbackCondition'))) | " +
        "$(Format-Value (Get-Field -Object $judge1 -Name @('running')))/$(Format-Value (Get-Field -Object $judge1 -Name @('reserved')))/$(Format-Value (Get-Field -Object $judge1 -Name @('queued'))) | " +
        "$(Format-Value (Get-Field -Object $judge2 -Name @('running')))/$(Format-Value (Get-Field -Object $judge2 -Name @('reserved')))/$(Format-Value (Get-Field -Object $judge2 -Name @('queued'))) | " +
        "$(Format-Value (Get-Field -Object $triggerBlock -Name @('activeWorkSeen'))) |")
}
$lines.Add("")
$lines.Add('`running` is work actually executing on that node and `reserved` is its in-flight permits, so both at their configured maximum is a saturated node, and `queued` above zero is work the node had accepted and not started. None of these is the number of rows the killed node had claimed: the outbox has no `claimed_by` column and the snapshot spans both nodes.')
$lines.Add("")
$lines.Add("| run id | cluster-wide claimed unfinished upper bound | max-in-flight (2 nodes) | stranded claimed rows / max-in-flight | attribution exact | age at kill p50/p95/max s | claimed unfinished exact |")
$lines.Add("|---|---:|---:|---:|---|---|---|")
foreach ($run in $runs) {
    $claimed = $run.claimedUnfinishedAtKill
    $age = Get-Field -Object $claimed -Name @('ageSecondsAtKill')
    $ageQuad = "$(Format-Number (Get-Field -Object $age -Name @('p50')))/$(Format-Number (Get-Field -Object $age -Name @('p95')))/$(Format-Number (Get-Field -Object $age -Name @('max')))"
    $lines.Add("| $($run.runId) | $(Format-Value $claimed.clusterWideUpperBound) | $(Format-Value $claimed.maxInFlightClusterWide) | $(Format-Number $run.normalized.strandedClaimedRowsOverMaxInFlight) | " +
        "$(Format-Value $claimed.attributionExact) | $ageQuad | $(Format-Value $claimed.claimedUnfinishedExact) |")
}
$lines.Add("")
$lines.Add('The claimed-unfinished count is a CLUSTER-WIDE UPPER BOUND and is never reported here as the killed node own claims. The outbox has no `claimed_by` column, so the snapshot cannot say which node held which row; every row in the PUBLISHING state at kill time on either node is counted. It is an upper bound twice over: it includes rows the surviving node was about to finish normally, and it is read from a snapshot taken around the kill rather than at the kill. The max-in-flight denominator is `mysqlMaxInFlightPerNode * 2` because this experiment runs two judge containers; the division is guarded, so a run whose max-in-flight is missing or zero reports `unavailable` rather than a substituted number.')
$lines.Add("")
$lines.Add("| run id | judge invocations | invocations are a lower bound | stale re-claims (attempts > 1) | stored result republishes | stale token completions | completion failures | claim calls | claimed rows | duplicate claim estimate | reclaimed rows after fault | harness estimate |")
$lines.Add("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($run in $runs) {
    $workCostBlock = $run.workCost
    $reclaimBlock = $run.reclaimAccounting
    $lines.Add("| $($run.runId) | $(Format-Value (Get-Field -Object $workCostBlock -Name @('judgeInvocations'))) | $(Format-Value (Get-Field -Object $workCostBlock -Name @('judgeInvocationsLowerBound'))) | " +
        "$(Format-Value (Get-Field -Object $workCostBlock -Name @('staleReclaims'))) | $(Format-Value (Get-Field -Object $workCostBlock -Name @('storedResultRepublishes'))) | " +
        "$(Format-Value (Get-Field -Object $workCostBlock -Name @('staleTokenCompletions'))) | $(Format-Value (Get-Field -Object $workCostBlock -Name @('completionFailure'))) | " +
        "$(Format-Value (Get-Field -Object $workCostBlock -Name @('claimCalls'))) | $(Format-Value (Get-Field -Object $workCostBlock -Name @('claimedRows'))) | " +
        "$(Format-Value (Get-Field -Object $workCostBlock -Name @('duplicateClaimEstimate'))) | $(Format-Value (Get-Field -Object $reclaimBlock -Name @('reclaimedRowsAfterFault'))) | " +
        "$(Format-Value (Get-Field -Object $reclaimBlock -Name @('reclaimedRowsHarnessEstimate'))) |")
}
$lines.Add("")
$lines.Add('The invocations column is a LOWER BOUND whenever its flag says yes, which it does on every run in this experiment: the SIGKILL took the JVM in-process counters with it. A counter that was never scraped is unknown, not zero, and the count here is what survived. The stale re-claims column is the durable evidence and does not depend on those counters - it is SUM(attempts - 1), read from the outbox. The reclaimed-rows columns are the count of submissions whose row was handed out again after the fault, computed two independent ways by the analyzer and the harness; their agreeing is the check that the durable accounting is complete.')
$lines.Add("")
$lines.Add("Read stale re-claims as recovery re-claims after the lease expired. It must not be read as concurrent duplicate CPU execution: the process holding the claim had been SIGKILLed, so it was not judging while the row was handed out again. The only shape that can produce two live judges on one submission is a paused process resumed after its lease expired, and that is a different experiment - see the limits section.")
$lines.Add("")

# --- 8. operational complexity ---------------------------------------------------------------------
$lines.Add("## 8. Operational complexity")
$lines.Add("")
$lines.Add("This section is the count of things an operator has to understand for the recovery to work, tied to the summary field each one comes from so nothing here is asserted without a source. It is the one section without a single number that orders the runs, because the runs did not differ in it: all of them were configured the same way and differ only in max-in-flight and claim timeout.")
$lines.Add("")
$lines.Add("| run id | configured fault-recovery knobs | trigger gate | escalation | node-ready gate | restart is automatic | down window configured s | down window measured s | drain s | sampling interval ms |")
$lines.Add("|---|---:|---|---|---|---|---:|---:|---:|---:|")
foreach ($run in $runs) {
    $recoveryTimeCells = $run.recoveryTimes
    $nodeReadyBasis = Get-Field -Object $run.anchors -Name @('nodeReadyAt', 'basis')
    $lines.Add("| $($run.runId) | $(Format-Value $run.faultRecoveryKnobCount) | $(Format-Value (Get-Field -Object $run.trigger -Name @('primaryCondition'))) | " +
        "$(Format-Value (Get-Field -Object $run.trigger -Name @('escalation'))) | $(Format-Value $nodeReadyBasis) | yes | " +
        "$(Format-Value $recoveryTimeCells.downDurationConfiguredSeconds) | $(Format-Value $recoveryTimeCells.downDurationSeconds) | " +
        "$(Format-Value $recoveryTimeCells.drainSeconds) | $(Format-Value $run.samplingIntervalMs) |")
}
$lines.Add("")
$mechanisms = @(
    "the claim lease: a claim is reclaimable by any later poll once its age passes the configured timeout, so the effective lease is the timeout plus up to one poll interval",
    "the trigger gate: the kill waits for observed work under the configured condition rather than firing at a fixed instant, so the down window starts from the kill and not from the schedule",
    "the restart gate: the node is not treated as back until the container is running AND readiness is UP AND Prometheus is scrapable AND the claim counter has been seen to advance",
    "the throughput recovery rule: three consecutive 5s windows at or above 90% of the pre-fault result RPS, at or after node readiness",
    "the backlog normalisation rule: each queue must hold at or below its own pre-fault p95 for 5s of wall clock, with no gap inside the holding streak wider than 2.5s, and the two queues are reported separately as well as combined",
    "the drain loop: the load stops and the harness waits for a zero backlog, which is a different wait from any of the above"
)
$lines.Add("Mechanisms in play, each one with its own failure mode and none of them removable by a configuration value alone:")
$lines.Add("")
foreach ($mechanism in $mechanisms) { $lines.Add("- $mechanism") }
$lines.Add("")
if ($capacityRows.Count -gt 0) {
    $lines.Add("Executor capacity during the measured phase, from the sampler gauges. This is the operational envelope the recovery had to work inside: after the kill, whichever node survived has to carry the whole offered load on its own, so its running gauge sitting at its configured maximum is expected rather than a finding.")
    $lines.Add("")
    $lines.Add("| run id | node | metric | samples | max | average |")
    $lines.Add("|---|---|---|---:|---:|---:|")
    foreach ($run in $runs) {
        foreach ($capacityRow in @($run.capacity)) {
            $lines.Add("| $($run.runId) | $($capacityRow.node) | $($capacityRow.metric) | $(Format-Value $capacityRow.samples) | $(Format-Value $capacityRow.max) | $(Format-Number $capacityRow.average) |")
        }
    }
    $lines.Add("")
    $capacityScopeText = Get-Field -Object $runs[0] -Name @('capacityScope')
    $lines.Add("Scope of those readings, quoted from the summary: $(Format-Value $capacityScopeText)")
} else {
    $lines.Add("Executor capacity gauges are unavailable for these runs: summary.capacity is absent, so nothing is reported about how saturated the surviving node was.")
}
$lines.Add("")

# --- cohorts A-E -----------------------------------------------------------------------------------
$lines.Add("## Fault-recovery cohorts A-E")
$lines.Add("")
$lines.Add("The five cohorts partition the measurement contest around the fault, in the order they happen. They are what this run measured instead of a steady state: the analyzer marks the run-level `measurement-steady` cohort unavailable on a fault-recovery run for exactly that reason, so these are the latency readings that replace it. Each is reported with its `L_total` percentiles and the share of its submissions that waited more than 5s and more than 10s, because the share is what an operator reasons about and the percentile is what a capacity argument needs.")
$lines.Add("")
$lines.Add("| run id | cohort | samples | L_total p50/p95/p99/max ms | L_result p50/p95/p99/max ms | L_scoreboard p50/p95/p99/max ms | over 5s | over 10s |")
$lines.Add("|---|---|---:|---|---|---|---:|---:|")
foreach ($run in $runs) {
    foreach ($cohortName in $cohortLetters.Keys) {
        $letter = $cohortLetters[$cohortName]
        $cohortEntry = $run.faultCohorts[$cohortName]
        $lines.Add((Get-CohortRow -Run $run -CohortName $cohortName -Letter $letter -CohortEntry $cohortEntry))
    }
}
$lines.Add("")
foreach ($run in $runs) {
    $postRecovery = $run.faultCohorts["post-recovery-steady"]
    if ($null -ne $postRecovery -and $null -ne $postRecovery.reading -and $null -ne $postRecovery.reading.window) {
        $windowBlock = $postRecovery.reading.window
        $lines.Add("- $($run.runId) cohort E window: $(Format-Value (Get-Field -Object $windowBlock -Name @('from'))) to $(Format-Value (Get-Field -Object $windowBlock -Name @('to'))), opens $(Format-Value (Get-Field -Object $windowBlock -Name @('preWindowDelaySeconds')))s after normalisation and needs $(Format-Value (Get-Field -Object $windowBlock -Name @('minimumWindowSeconds')))s of load to count, actual $(Format-Value (Get-Field -Object $windowBlock -Name @('actualSeconds')))s over $(Format-Value (Get-Field -Object $windowBlock -Name @('sampleSpanSeconds')))s of samples ($(Format-Value (Get-Field -Object $windowBlock -Name @('sampleCount'))) samples). Basis as recorded: $(Format-Value (Get-Field -Object $windowBlock -Name @('basis')))")
    }
}
$lines.Add("")
$lines.Add("### Run-level cohorts, for the two readings that span the fault")
$lines.Add("")
$lines.Add('These are the analyzer own partitions of the same contest. `post-fault-arrivals` is the whole outage as one cohort and `all` is the entire measured contest; both are reported here because a reader who wants one number for the outage will look for them, and both are queueing measurements for the same reason as above.')
$lines.Add("")
$lines.Add("| run id | cohort | samples | L_total p50/p95/p99/max ms | L_result p50/p95/p99/max ms | L_scoreboard p50/p95/p99/max ms | available | reason when unavailable |")
$lines.Add("|---|---|---:|---|---|---|---|---|")
foreach ($run in $runs) {
    foreach ($cohortName in $generalCohorts) {
        $generalEntry = $run.generalCohorts[$cohortName]
        if ($null -eq $generalEntry.reading) {
            $lines.Add("| $($run.runId) | $cohortName | unavailable | unavailable | unavailable | unavailable | no | $($generalEntry.unavailableReason) |")
            continue
        }
        $reading = $generalEntry.reading
        $lines.Add("| $($run.runId) | $cohortName | $(Format-Value $reading.sampleCount) | $(Format-PercentileQuad $reading.total) | $(Format-PercentileQuad $reading.result) | " +
            "$(Format-PercentileQuad $reading.scoreboard) | yes | - |")
    }
}
$lines.Add("")

# --- direct 4s versus 10s --------------------------------------------------------------------------
$lines.Add("## Direct comparison: MIF64 / 4s versus MIF64 / 10s")
$lines.Add("")
if (-not $deltaAvailable) {
    $lines.Add("This comparison cannot be made from the runs given to this comparer: a max-in-flight-64 run at a 4s claim timeout and one at a 10s claim timeout are both required, and $(if ($null -eq $runFourSeconds) { 'the 4s run is missing' } else { 'the 10s run is missing' }). Nothing is substituted for it.")
} else {
    $lines.Add("- 4s run: $($runFourSeconds.runId)")
    $lines.Add("- 10s run: $($runTenSeconds.runId)")
    $lines.Add("- Delta definition: the 4s run value MINUS the 10s run value, so a negative delta means 4s was lower or faster.")
    $lines.Add("- Load comparability: $loadComparableReason")
    $lines.Add("")
    $lines.Add("| metric | 4s | 10s | delta (4s - 10s) | sampling band | verdict |")
    $lines.Add("|---|---:|---:|---:|---:|---|")
    foreach ($deltaRow in $deltaRows.ToArray()) {
        $lines.Add("| $($deltaRow.metric) | $(Format-Number $deltaRow.value4s) | $(Format-Number $deltaRow.value10s) | $(Format-Number $deltaRow.delta4sMinus10s) | $(Format-Number $deltaRow.samplingBand) | $($deltaRow.verdict) |")
    }
    $lines.Add("")
    $lines.Add("The verdict column applies the same rule to every metric rather than picking a winner per row. A difference is called only when it is larger than the band that run's own measurement can resolve - two sampling intervals for the recovery times, one interval of arrivals at the offered load for the backlog peak, and a whole deterministic latency class for the percentile, because those values are step-valued and a between-step difference is the same amount of judge work. Where a difference is inside that band the verdict says so instead of naming a winner, and on the raw RPS and raw latency columns the verdict refuses outright when the two runs were not offered the same load.")
    $lines.Add("")
    $lines.Add("### Normalized metrics, side by side")
    $lines.Add("")
    $lines.Add("These are the same five ratios the normalization section defines, computed per run and differenced the same way. They are the only throughput-and-latency comparison between two runs at different offered loads that this report treats as valid, because each divides by that run's own baseline. The ratio's own sampling band is not carried in the summary, so the verdict here gives a direction and says plainly that it is not a significance call.")
    $lines.Add("")
    $lines.Add("| metric | 4s | 10s | delta (4s - 10s) | verdict |")
    $lines.Add("|---|---:|---:|---:|---|")
    foreach ($normalizedRow in $deltaNormalizedRows.ToArray()) {
        $lines.Add("| $($normalizedRow.metric) | $(Format-Number $normalizedRow.value4s) | $(Format-Number $normalizedRow.value10s) | $(Format-Number $normalizedRow.delta4sMinus10s) | $($normalizedRow.verdict) |")
    }
}
$lines.Add("")

# --- MIF16, interpreted separately -----------------------------------------------------------------
$lines.Add("## MIF16 / 2500ms, interpreted separately")
$lines.Add("")
if ($null -eq $runSixteen) {
    $lines.Add("No max-in-flight-16 run at a 2500ms claim timeout was given to this comparer, so there is nothing to interpret separately.")
} else {
    $lines.Add("This run is reported on its own absolute numbers and its own normalized metrics only, and it is deliberately kept out of the table above.")
    $lines.Add("")
    $lines.Add("It is NOT valid to compare its raw RPS or its raw latency against either MIF64 run: it was offered $(Format-Value $runSixteen.offeredRps) RPS while the MIF64 runs were offered $(Format-Value $(if ($null -ne $runFourSeconds) { $runFourSeconds.offeredRps } else { $null })) RPS. A different offered load changes the backlog, the queueing delay and the share of submissions that arrive during the outage, so a raw difference between them is a difference in the load and in the configuration together, with no way to attribute it. Only the normalized metrics - each divided by that run's own pre-fault baseline - are meaningful across the two groups, and even those are read as directions.")
    $lines.Add("")
    $lines.Add("| metric | value |")
    $lines.Add("|---|---:|")
    $lines.Add("| run id | $($runSixteen.runId) |")
    $lines.Add("| max-in-flight per node | $(Format-Value $runSixteen.mysqlMaxInFlightPerNode) |")
    $lines.Add("| claim timeout | $(Format-Value $runSixteen.mysqlClaimTimeout) |")
    $lines.Add("| offered RPS | $(Format-Value $runSixteen.offeredRps) |")
    $lines.Add("| accepted | $(Format-Value $runSixteen.accepted) |")
    $lines.Add("| unique submissions | $(Format-Value $runSixteen.uniqueSubmissions) |")
    $lines.Add("| results | $(Format-Value $runSixteen.results) |")
    $lines.Add("| scoreboard applied | $(Format-Value $runSixteen.scoreboardApplied) |")
    $lines.Add("| integrity chain holds | $(Format-Value $runSixteen.chainHolds) |")
    $lines.Add("| pre-fault result RPS | $(Format-Value $runSixteen.throughput.preFaultResultRps) |")
    $lines.Add("| first stale reclaim (T_stale) | $(Format-Seconds $runSixteen.recoveryTimes.T_staleSeconds) |")
    $lines.Add("| backlog normalization (combined) | $(Format-Seconds $runSixteen.recoveryTimes.T_backlogNormalizationSeconds) |")
    $lines.Add("| throughput recovery | $(Format-Seconds $runSixteen.recoveryTimes.T_throughputRecoverySeconds) |")
    $lines.Add("| judge backlog peak | $(if ($null -ne $runSixteen.backlogSeries) { Format-Value $runSixteen.backlogSeries.judgeBacklogPeak } else { "unavailable" }) |")
    $lines.Add("| scoreboard pending peak | $(if ($null -ne $runSixteen.backlogSeries) { Format-Value $runSixteen.backlogSeries.scoreboardPendingPeak } else { "unavailable" }) |")
    $lines.Add("| cluster-wide claimed unfinished upper bound | $(Format-Value $runSixteen.claimedUnfinishedAtKill.clusterWideUpperBound) |")
    $lines.Add("| reclaimed rows after fault | $(Format-Value (Get-Field -Object $runSixteen.reclaimAccounting -Name @('reclaimedRowsAfterFault'))) |")
    $lines.Add("| drain | $(Format-Seconds $runSixteen.recoveryTimes.drainSeconds) |")
    $lines.Add("")
    $lines.Add("Its normalized metrics, which is the part that can be read beside the MIF64 group:")
    $lines.Add("")
    $lines.Add("| metric | value |")
    $lines.Add("|---|---:|")
    foreach ($normalizedName in @(
        @("backlogPeakOverAcceptedRps", "backlog peak / accepted RPS"),
        @("recoveredRowsPerSecond", "recovered rows / second"),
        @("faultCohortP99OverConfiguredTimeout", "fault cohort p99 / configured timeout"),
        @("strandedClaimedRowsOverMaxInFlight", "stranded claimed rows / max-in-flight"),
        @("recoveryTimeOverPreFaultThroughput", "recovery time / pre-fault throughput")
    )) {
        $metricKey = $normalizedName[0]
        $metricLabel = $normalizedName[1]
        $metricValue = Get-Field -Object $runSixteen.normalized -Name @($metricKey)
        $lines.Add("| $metricLabel | $(Format-Number $metricValue) |")
    }
    $lines.Add("")
    $lines.Add("The claim timeout is the other reason this run is separate: at 2500ms it is shorter than both MIF64 timeouts, so its stale reclaim fires earlier relative to the kill for a reason that has nothing to do with max-in-flight.")
}
$lines.Add("")

# --- normalization metrics -------------------------------------------------------------------------
$lines.Add("## Normalization metrics")
$lines.Add("")
$lines.Add('Five ratios, defined here once so every cell in the comparisons above has a stated meaning. Each is a division of two quantities the summary carries, each is guarded, and each is `null` in the JSON and `unavailable` here when either side is missing or the denominator is zero. A zero would have read as no extra cost or no extra delay, which is the opposite of what an unmeasured value means.')
$lines.Add("")
$lines.Add('- `backlog peak / accepted RPS` - the peak from recovery-samples.csv over the run own pre-fault accepted RPS (faultRecovery.preFault.resultRps). It is the seconds of arriving work the peak backlog represented, which is why it is the one peak reading that survives a load difference between runs.')
$lines.Add('- `recovered rows / second` - the submissions whose outbox row was handed out again after the fault, divided by the combined backlog normalization time. The rate at which the stranded work was cleared.')
$lines.Add('- `fault cohort p99 / configured timeout` - the fault-down cohort L_result p99 over the configured claim timeout in milliseconds. Above 1 means submissions that arrived during the outage waited longer than the lease the configuration implies; it is the reading that ties the timeout value to the customer-visible latency.')
$lines.Add('- `stranded claimed rows / max-in-flight` - the cluster-wide claimed-unfinished upper bound over mysqlMaxInFlightPerNode * 2. It says what share of the cluster whole claim capacity was left unfinished by the kill. Guarded against a missing or zero max-in-flight.')
$lines.Add('- `recovery time / pre-fault throughput` - the combined backlog normalization time over the pre-fault result RPS. It is a shape ratio with units of seconds per result-per-second, not a duration: it says how much recovery time a run bought per unit of throughput it was carrying, so two runs at different loads can be compared on how the recovery scaled with the work in flight.')
$lines.Add("")
$lines.Add("| run id | backlog peak / accepted RPS | judge backlog peak / accepted RPS | scoreboard pending peak / accepted RPS | recovered rows / second | backlog drain rows / second | fault cohort p99 / configured timeout | stranded claimed rows / max-in-flight | recovery time / pre-fault throughput |")
$lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($run in $runs) {
    $normalizedBlock = $run.normalized
    $lines.Add("| $($run.runId) | $(Format-Number $normalizedBlock.backlogPeakOverAcceptedRps) | $(Format-Number $normalizedBlock.judgeBacklogPeakOverAcceptedRps) | " +
        "$(Format-Number $normalizedBlock.scoreboardPendingPeakOverAcceptedRps) | $(Format-Number $normalizedBlock.recoveredRowsPerSecond) | " +
        "$(Format-Number $normalizedBlock.backlogDrainRowsPerSecond) | $(Format-Number $normalizedBlock.faultCohortP99OverConfiguredTimeout) | " +
        "$(Format-Number $normalizedBlock.strandedClaimedRowsOverMaxInFlight) | $(Format-Number $normalizedBlock.recoveryTimeOverPreFaultThroughput) |")
}
$lines.Add("")
$lines.Add("The backlog drain rate is reported beside the recovered-rows rate because the two measure different things and only one of them is about the outbox: recovered rows per second counts re-claimed outbox rows, while backlog drain rows per second is the peak queue above its own pre-fault p95 divided by the same recovery time, which is the rate the queue emptied at regardless of how the rows got there.")
$lines.Add("")

# --- limits ----------------------------------------------------------------------------------------
$lines.Add("## Limits")
$lines.Add("")
foreach ($statement in $limitStatements) { $lines.Add("- $statement") }
$lines.Add("")
$lines.Add("Each run's own unavailable[] entries, deduplicated across runs because the same reason is written into every summary. These are the statements the analyzer makes about what its own numbers do not cover, reproduced rather than paraphrased.")
$lines.Add("")
if ($limitEntries.Count -eq 0) {
    $lines.Add("None of the runs carried an unavailable[] entry.")
} else {
    $lines.Add("| source | first run carrying it | entry |")
    $lines.Add("|---|---|---|")
    foreach ($limitEntry in $limitEntries.ToArray()) {
        $lines.Add("| $($limitEntry.source) | $($limitEntry.runId) | $($limitEntry.text) |")
    }
}
$lines.Add("")

# --- what was not in the summaries -------------------------------------------------------------------
$lines.Add("## Values the summaries did not carry")
$lines.Add("")
if ($unavailableValues.Count -eq 0) {
    $lines.Add("None. Every value this report prints was read from a summary.json field or from recovery-samples.csv.")
} else {
    $lines.Add('These are printed as `unavailable` above and as `null` in the JSON. Each entry names the field and the summary.json path it was looked for at, or the file that was absent. Nothing in this list was estimated, defaulted or computed from a different file.')
    $lines.Add("")
    $lines.Add("| run id | value |")
    $lines.Add("|---|---|")
    foreach ($unavailableEntry in $unavailableValues.ToArray()) {
        $lines.Add("| $($unavailableEntry.runId) | $($unavailableEntry.value) |")
    }
}
$lines.Add("")

# --- scope -----------------------------------------------------------------------------------------
$lines.Add("## Scope and how to read these numbers")
$lines.Add("")
$lines.Add("Three different windows are in play and reading them as one is the mistake this section exists to prevent. The recovery times are measured from the fault instant, so they contain the down window. The cohort percentiles cover the cohorts defined around the fault and not a steady state, because there is no steady state in a run with a kill inside its measured window - the analyzer marks the run-level measurement-steady cohort unavailable for exactly that reason, and the fault-recovery cohorts are what replaces it. The counts (accepted, results, scoreboard applied, reclaimed rows) span the whole measured phase, which includes the drain, so they are not consistent with either of the other two windows.")
$lines.Add("")
$lines.Add("What this comparison can support: whether the correctness chain held, how the recovery times and the peak backlog differed between configurations at the same offered load, and how the extra claim and DB work scaled. What it cannot support: a claim about concurrent duplicate CPU execution or about fencing (the killed process was not running), a claim about the SIGKILLed node's own counters (they died with the process and what remains is a lower bound), and any raw RPS or latency comparison between runs that were not offered the same load.")
$lines.Add("")

$lines | Set-Content $markdownPath -Encoding utf8

Write-Host "Wrote $comparisonPath and $markdownPath"
exit 0
