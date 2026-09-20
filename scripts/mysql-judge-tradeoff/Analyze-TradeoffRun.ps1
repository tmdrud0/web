[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RunDirectory,
    # A stage where the API refused a share of submissions is measuring the rate limiter, not judge
    # capacity, so it is reported but kept out of the knee. The share is a parameter because the
    # line between "a few KOs" and "the limiter is the story" is a judgement, and it is worth being
    # able to move it without editing the script.
    [double]$ApiRateLimitShare = 0.01
)

$ErrorActionPreference = "Stop"

# The measurement workload's deterministic judge profile: 95% of submissions are judged in 50ms and
# 5% in 2000ms, keyed on the code. A discarded execution was one or the other, so these bracket the
# judge time a duplicate execution cost. They are constants of this workload, not of the run, and the
# harness passes the same profile in every run.
$DuplicateJudgeMillisFloor = 50
$DuplicateJudgeMillisCeiling = 2000

$runPath = (Resolve-Path $RunDirectory).Path
$latencyPath = Join-Path $runPath "latency.csv"
$eventsPath = Join-Path $runPath "events.json"
$verificationPath = Join-Path $runPath "db-verification.json"
$parametersPath = Join-Path $runPath "parameters.json"

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($null -eq $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Max(0, [math]::Ceiling($sorted.Count * $Percentile) - 1)
    return [math]::Round([double]$sorted[$index], 3)
}

function Get-RatioOrNull {
    param($Value, $Denominator, [int]$Places = 6)
    # A rate over zero accepted submissions is not zero, it is undefined; the caller writes null and
    # the report says unavailable rather than publishing a zero that would read as "none happened".
    if ($null -eq $Value -or $null -eq $Denominator -or $Denominator -le 0) { return $null }
    return [math]::Round($Value / $Denominator, $Places)
}

function Get-LatencySummary {
    param([object[]]$Rows)
    $result = [ordered]@{}
    foreach ($name in @("L_result_ms", "L_scoreboard_ms", "L_total_ms")) {
        $values = @($Rows | ForEach-Object {
            $value = 0.0
            if ([double]::TryParse([string]$_.$name, [Globalization.NumberStyles]::Float,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { $value }
        })
        $result[$name] = [ordered]@{
            count = $values.Count
            p50 = Get-Percentile $values 0.50
            p95 = Get-Percentile $values 0.95
            p99 = Get-Percentile $values 0.99
            max = if ($values.Count -gt 0) { [math]::Round(($values | Measure-Object -Maximum).Maximum, 3) } else { $null }
        }
    }
    return $result
}

function Get-LatencyClassSummary {
    param([object[]]$Rows)
    $result = [ordered]@{}
    foreach ($latencyClass in @("fast", "slow")) {
        $classRows = @($Rows | Where-Object { $_.latencyClass -eq $latencyClass })
        $result[$latencyClass] = Get-LatencySummary $classRows
        $result[$latencyClass]["submissionCount"] = $classRows.Count
    }
    $unclassified = @($Rows | Where-Object { @("fast", "slow") -notcontains $_.latencyClass }).Count
    $result["unclassifiedSubmissionCount"] = $unclassified
    return $result
}

# --- fault recovery (SIGKILL) helpers -------------------------------------------------------------

function ConvertTo-LongOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = 0L
    if (-not [long]::TryParse($text, [ref]$parsed)) { return $null }
    return $parsed
}

function ConvertTo-DateTimeOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($text, [ref]$parsed)) { return $null }
    return $parsed
}

# The timestamps that came out of MySQL are UTC written without an offset, so parsing them as they
# stand would read them in the machine's own zone - nine hours away here, which turns a reclaimed
# submission's latency into a large negative number rather than an error. This is the same convention
# Get-EpochMillis applies to submittedAt.
function ConvertTo-UtcNaiveOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($text + "Z", [ref]$parsed)) { return $null }
    return $parsed
}

# One shape for the two readers of the recovery series: recovery-samples.csv when the fault phase wrote
# it, otherwise the 1s timeseries the sampler was writing all along. A run that died mid-phase has the
# second and not the first, and what it does have is worth computing rather than discarding. The field
# names are the harness's own (unfinished/unapplied) so the derivation below is the same rule.
#
# The recovery series stops when the fault phase stops, and the backlog is allowed to normalise inside
# the drain that follows: the spec's own failure condition is "not normalised within 600s", not "not
# normalised before the load stopped". So any drain sample the recovery series does not already cover is
# appended. Without that, a run whose backlog crossed its baseline a few seconds into the drain is
# reported as never having normalised - a failure invented by the reader rather than measured. The
# consequence of reading a normalisation there is carried by cohort E, which reports itself unavailable
# when too little measured load remained, so the late instant cannot pass as recovered steady state.
function Get-RecoverySamples {
    $samples = New-Object System.Collections.Generic.List[object]
    $samplesPath = Join-Path $runPath "recovery-samples.csv"
    if (Test-Path $samplesPath) {
        foreach ($row in @(Import-Csv $samplesPath)) {
            $at = ConvertTo-DateTimeOrNull $row.at
            if ($null -eq $at) { continue }
            $samples.Add([pscustomobject]@{
                at = $at
                unfinished = ConvertTo-LongOrNull $row.judgeBacklog
                unapplied = ConvertTo-LongOrNull $row.scoreboardPending
                accepted = ConvertTo-LongOrNull $row.accepted
                results = ConvertTo-LongOrNull $row.results
            })
        }
        if ($samples.Count -gt 0) {
            $lastAt = $samples[0].at
            foreach ($sample in $samples) { if ($sample.at -gt $lastAt) { $lastAt = $sample.at } }
            $appended = 0
            foreach ($row in $timeseries) {
                if ($row.phase -eq "warmup") { continue }
                $at = ConvertTo-DateTimeOrNull $row.timestamp
                if ($null -eq $at -or $at -le $lastAt) { continue }
                $samples.Add([pscustomobject]@{
                    at = $at
                    unfinished = ConvertTo-LongOrNull $row.unfinishedOutbox
                    unapplied = ConvertTo-LongOrNull $row.unappliedScoreboard
                    accepted = ConvertTo-LongOrNull $row.acceptedTotal
                    results = ConvertTo-LongOrNull $row.resultsTotal
                })
                $appended++
            }
            $sorted = @($samples | Sort-Object -Property at)
            $script:recoverySamplesAppended = $appended
            # No comma-wrapped return here: the caller wraps the result in @(), and the two together turn
            # the series into a one-element array holding the array, which reads downstream as one sample.
            return $sorted
        }
    }
    foreach ($row in $timeseries) {
        # The warm-up wrote to a separate contest, so its backlog belongs to neither this baseline nor
        # this normalisation; leaving those rows in would lift the baseline out of the run under test.
        if ($row.phase -eq "warmup") { continue }
        $at = ConvertTo-DateTimeOrNull $row.timestamp
        if ($null -eq $at) { continue }
        $samples.Add([pscustomobject]@{
            at = $at
            unfinished = ConvertTo-LongOrNull $row.unfinishedOutbox
            unapplied = ConvertTo-LongOrNull $row.unappliedScoreboard
            accepted = ConvertTo-LongOrNull $row.acceptedTotal
            results = ConvertTo-LongOrNull $row.resultsTotal
        })
    }
    return $samples.ToArray()
}

# A window's rows, selected on submission time. The indexed row's own timestamp is read into a local
# before the inner comparison: inside a nested filter `$_` is the other object, and the failure is
# silent - no submission matches and the cohort reports zero samples as if none had been submitted.
function Get-SubmissionWindow {
    param([object[]]$LatencyIndexed, $From, $To)
    if ($null -eq $From) { return @() }
    $fromMillis = ([datetimeoffset]$From).ToUnixTimeMilliseconds()
    $toMillis = if ($null -eq $To) { [long]::MaxValue } else { ([datetimeoffset]$To).ToUnixTimeMilliseconds() }
    return @($LatencyIndexed | Where-Object {
        if ($null -eq $_.millis) { return $false }
        $atMillis = [long]$_.millis
        return ($atMillis -ge $fromMillis -and $atMillis -lt $toMillis)
    } | ForEach-Object { $_.row })
}

function Get-CohortLatency {
    param([object[]]$Rows)
    $summary = Get-LatencySummary $Rows
    $totals = @($Rows | ForEach-Object {
        $value = 0.0
        if ([double]::TryParse([string]$_.L_total_ms, [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { $value }
    })
    $over5s = @($totals | Where-Object { $_ -gt 5000 }).Count
    $over10s = @($totals | Where-Object { $_ -gt 10000 }).Count
    $summary["submissionCount"] = $Rows.Count
    $summary["over5sCount"] = $over5s
    $summary["over10sCount"] = $over10s
    # The ratios are absent rather than zero for an empty cohort: a cohort with no sample has no share,
    # and a published 0.0 would read as "none of them were slow".
    $summary["over5sRatio"] = Get-RatioOrNull $over5s $Rows.Count
    $summary["over10sRatio"] = Get-RatioOrNull $over10s $Rows.Count
    $summary["byLatencyClass"] = Get-LatencyClassSummary $Rows
    $summary["available"] = ($Rows.Count -gt 0)
    return $summary
}

function Get-SustainStreak {
    param(
        [object[]]$Samples,
        [string]$Field,
        $Ceiling,
        [int]$SustainSeconds = 5,
        [double]$MaxSampleGapSeconds = 2.5
    )
    # Deliberately the same shape as the harness's own search, for the same reason: both recompute one
    # instant from one sample series, and a disagreement is then a finding rather than a rounding
    # difference. "Held for N seconds" is grown over wall clock rather than counted in samples - N
    # samples 1s apart span N-1 seconds, so a sample count with a span floor excluded the well-behaved
    # series it was written for and fired only on series with a hole. The gap bound keeps the streak
    # honest: one missed tick is tolerated, a stalled sampler is not, and the widest covered gap comes
    # back with the answer so its resolution is reportable.
    for ($i = 0; $i -lt $Samples.Count; $i++) {
        $maxGap = 0.0
        for ($j = $i; $j -lt $Samples.Count; $j++) {
            $value = $Samples[$j].$Field
            if ($null -eq $value -or $value -gt $Ceiling) { break }
            if ($j -gt $i) {
                $gap = ($Samples[$j].at - $Samples[$j - 1].at).TotalSeconds
                if ($gap -gt $MaxSampleGapSeconds) { break }
                if ($gap -gt $maxGap) { $maxGap = $gap }
            }
            $span = ($Samples[$j].at - $Samples[$i].at).TotalSeconds
            if ($span -ge $SustainSeconds) {
                return [pscustomobject]@{
                    at = $Samples[$i].at
                    spanSeconds = [math]::Round($span, 3)
                    maxGapSeconds = [math]::Round($maxGap, 3)
                    sampleCount = $j - $i + 1
                }
            }
        }
    }
    return $null
}

function Get-BacklogNormalizationFrom {
    param(
        [object[]]$Samples,
        $From,
        $JudgeP95,
        $ScoreboardP95,
        [int]$SustainSeconds = 5,
        [double]$MaxSampleGapSeconds = 2.5
    )
    $result = [pscustomobject]@{
        judgeNormalizedAt = $null; scoreboardNormalizedAt = $null
        combinedNormalizedAt = $null; sustainSpanSeconds = $null
        maxSampleGapSeconds = $null; sustainSampleCount = $null
    }
    if ($null -eq $From) { return $result }
    $window = @($Samples | Where-Object { $_.at -ge $From })
    if ($null -eq $JudgeP95 -or $null -eq $ScoreboardP95 -or $window.Count -lt 2) { return $result }
    $judge = Get-SustainStreak -Samples $window -Field "unfinished" -Ceiling $JudgeP95 `
        -SustainSeconds $SustainSeconds -MaxSampleGapSeconds $MaxSampleGapSeconds
    $scoreboard = Get-SustainStreak -Samples $window -Field "unapplied" -Ceiling $ScoreboardP95 `
        -SustainSeconds $SustainSeconds -MaxSampleGapSeconds $MaxSampleGapSeconds
    if ($null -ne $judge) {
        $result.judgeNormalizedAt = $judge.at
        $result.sustainSpanSeconds = $judge.spanSeconds
        $result.maxSampleGapSeconds = $judge.maxGapSeconds
        $result.sustainSampleCount = $judge.sampleCount
    }
    if ($null -ne $scoreboard) { $result.scoreboardNormalizedAt = $scoreboard.at }
    if ($null -ne $result.judgeNormalizedAt -and $null -ne $result.scoreboardNormalizedAt) {
        $result.combinedNormalizedAt = if ($result.judgeNormalizedAt -gt $result.scoreboardNormalizedAt) {
            $result.judgeNormalizedAt
        } else { $result.scoreboardNormalizedAt }
    }
    return $result
}

function Get-FaultRecoveryAnalysis {
    param([object[]]$LatencyIndexed)

    $unavailableFault = New-Object System.Collections.Generic.List[string]
    $unavailableFault.Add("the killed node's in-process Prometheus counters are lower bounds: SIGKILL took the JVM, so every invocation, duration and claim increment between the pre-fault scrape and the kill died with the process, and a lost counter is not a zero. The durable evidence is the outbox attempts column and the final row state.")
    $unavailableFault.Add("attempts > 1 counts recovery re-claims after the static lease expired, not concurrent duplicate CPU execution: the process holding the claim was SIGKILLed, so it did not keep judging. Testing true concurrent duplicate execution and fencing needs a separate docker pause -> wait past the claim timeout -> unpause experiment, which this round does not run.")

    $faultAt = ConvertTo-DateTimeOrNull $events.faultInjectedAt
    $restartRequestedAt = ConvertTo-DateTimeOrNull $events.restartRequestedAt
    $nodeReadyAt = ConvertTo-DateTimeOrNull $events.nodeReadyAt
    # The run's own during-run reading of the same instant. Kept only to be compared against the
    # recomputation: nothing below is gated on it, because it stops with the fault phase and cannot
    # contain a normalisation that landed in the drain.
    $harnessNormalizedAt = ConvertTo-DateTimeOrNull $events.backlogNormalizedAt

    $samples = @(Get-RecoverySamples)
    if ($null -eq $recoverySamplesAppended) { $recoverySamplesAppended = 0 }
    $baselineFrom = if ($null -eq $faultAt) { $null } else { $faultAt.AddSeconds(-30) }
    $baselineTo = if ($null -eq $faultAt) { $null } else { $faultAt.AddSeconds(-5) }
    $baseline = if ($null -eq $baselineFrom) { @() } else { @($samples | Where-Object { $_.at -ge $baselineFrom -and $_.at -lt $baselineTo }) }
    # Filtered before the percentile call: a [double[]] parameter coerces a $null element to 0, which
    # would put a zero row into the baseline of a backlog that never was zero.
    $judgeValues = @($baseline | Where-Object { $null -ne $_.unfinished } | ForEach-Object { [double]$_.unfinished })
    $scoreboardValues = @($baseline | Where-Object { $null -ne $_.unapplied } | ForEach-Object { [double]$_.unapplied })
    $judgeP95 = Get-Percentile $judgeValues 0.95
    $scoreboardP95 = Get-Percentile $scoreboardValues 0.95

    $preFaultResultRps = $null
    if ($judgeValues.Count -ge 2) {
        $first = $baseline[0]; $last = $baseline[-1]
        $span = ($last.at - $first.at).TotalSeconds
        if ($span -gt 0 -and $null -ne $first.results -and $null -ne $last.results) {
            $preFaultResultRps = [math]::Round(($last.results - $first.results) / $span, 6)
        }
    }

    # The harness derived both of these during the run so it could decide when to stop. They are
    # recomputed here from the samples, which is what makes them reproducible after the fact, and the
    # harness's own value is kept beside the recomputed one so a disagreement is visible rather than
    # resolved by preferring whichever was written last.
    $sustainSeconds = 5
    $maxSampleGapSeconds = 2.5
    $maxWindowSpanSeconds = 10
    # The reported instants are searched from max(faultInjectedAt, nodeReadyAt): cohort D is defined as
    # nodeReadyAt -> backlogNormalizedAt, and nothing before the replacement node was serving can be
    # the end of the outage. The ungated search is run as well and reported when it lands earlier, since
    # a surviving node draining the backlog alone is a finding about the lease rather than an artefact.
    $searchFromAt = $faultAt
    if ($null -ne $faultAt -and $null -ne $nodeReadyAt -and $nodeReadyAt -gt $faultAt) { $searchFromAt = $nodeReadyAt }
    $gated = Get-BacklogNormalizationFrom -Samples $samples -From $searchFromAt -JudgeP95 $judgeP95 `
        -ScoreboardP95 $scoreboardP95 -SustainSeconds $sustainSeconds -MaxSampleGapSeconds $maxSampleGapSeconds
    $ungated = Get-BacklogNormalizationFrom -Samples $samples -From $faultAt -JudgeP95 $judgeP95 `
        -ScoreboardP95 $scoreboardP95 -SustainSeconds $sustainSeconds -MaxSampleGapSeconds $maxSampleGapSeconds
    $judgeNormalizedAt = $gated.judgeNormalizedAt
    $scoreboardNormalizedAt = $gated.scoreboardNormalizedAt
    $recomputedNormalizedAt = $gated.combinedNormalizedAt
    $earliestNormalizedAt = $null
    if ($null -ne $ungated.combinedNormalizedAt -and
        ($null -eq $recomputedNormalizedAt -or $ungated.combinedNormalizedAt -lt $recomputedNormalizedAt)) {
        $earliestNormalizedAt = $ungated.combinedNormalizedAt
    }

    $rolling = New-Object System.Collections.Generic.List[object]
    $recomputedRecoveredAt = $null
    $rollingSpanMaxSeconds = $null
    if ($null -ne $preFaultResultRps -and $preFaultResultRps -gt 0) {
        $thresholdRps = 0.90 * $preFaultResultRps
        for ($i = 1; $i -lt $samples.Count; $i++) {
            if ($null -eq $samples[$i].results) { continue }
            $j = $i - 1
            while ($j -gt 0 -and ($samples[$i].at - $samples[$j].at).TotalSeconds -lt 5) { $j-- }
            $span = ($samples[$i].at - $samples[$j].at).TotalSeconds
            # Bounded above as well as below: walking back only to "at least 5s earlier" lets a sampling
            # hole pass as one long five second rate, and the recovery instant read off it would then be
            # an artefact of the hole.
            if ($span -lt 5 -or $span -gt $maxWindowSpanSeconds) { continue }
            if ($null -eq $samples[$j].results) { continue }
            $rolling.Add([pscustomobject]@{
                at = $samples[$i].at
                rps = ($samples[$i].results - $samples[$j].results) / $span
                span = $span
            })
        }
        $rollingSpanMaxSeconds = $null
        if ($rolling.Count -gt 0) {
            $rollingSpanMaxSeconds = [math]::Round((($rolling | Measure-Object -Property span -Maximum).Maximum), 3)
        }
        for ($i = 0; $i -le ($rolling.Count - 3); $i++) {
            if ($null -ne $searchFromAt -and $rolling[$i].at -lt $searchFromAt) { continue }
            $held = $true
            for ($j = $i; $j -lt ($i + 3); $j++) { if ($rolling[$j].rps -lt $thresholdRps) { $held = $false } }
            if ($held) { $recomputedRecoveredAt = $rolling[$i].at; break }
        }
    }

    # Cohort C is built from the durable attempts column, not from the kill snapshot. The snapshot has
    # no claim owner, so its submission list holds every node's in-flight rows and using it here would
    # put the surviving node's ordinary work into the killed node's cohort; the snapshot count is kept
    # beside this one as an upper bound instead.
    $reclaimed = @($LatencyIndexed | Where-Object {
        if ($null -eq $_.millis) { return $false }
        $attempts = ConvertTo-LongOrNull $_.row.attempts
        if ($null -eq $attempts -or $attempts -le 1) { return $false }
        if ($null -eq $faultAt) { return $false }
        return ([long]$_.millis -ge $faultAt.ToUnixTimeMilliseconds())
    } | ForEach-Object { $_.row })

    $cohortsFault = [ordered]@{}
    # A. pre-fault steady, B. fault/down arrivals, C. reclaimed, D. post-restart recovery,
    # E. post-recovery steady. F is the whole measurement contest and is reported as `all` above; it is
    # kept out of this table so its queueing tail cannot be read as any of A-E.
    $steadyFrom = if ($null -eq $faultAt) { $null } else { $faultAt.AddSeconds(-30) }
    $steadyTo = if ($null -eq $faultAt) { $null } else { $faultAt.AddSeconds(-5) }
    $cohortsFault["pre-fault-steady"] = Get-CohortLatency (Get-SubmissionWindow $LatencyIndexed $steadyFrom $steadyTo)
    $cohortsFault["fault-down-arrivals"] = Get-CohortLatency (Get-SubmissionWindow $LatencyIndexed $faultAt $nodeReadyAt)
    $cohortsFault["reclaimed-after-fault"] = Get-CohortLatency $reclaimed
    # Cohort D ends at the recomputed instant, not the run's own reading: a run whose reading is null
    # because the normalisation landed in the drain would otherwise make cohort D the whole tail of the
    # run rather than the recovery interval it is defined as.
    $cohortsFault["post-restart-recovery"] = Get-CohortLatency (Get-SubmissionWindow $LatencyIndexed $nodeReadyAt $recomputedNormalizedAt)
    # The post-recovery window is recomputed here rather than read from the run. The run decided it while
    # the measurement was still going, from a normalisation it could only have seen before the load
    # stopped; a normalisation that lands in the drain is therefore reported by the run as "never", which
    # would hand cohort E a reason ("no normalisation was observed") that the recomputed series contradicts.
    # The floor is what actually applies: too little measured load remained after the window opened for the
    # window to be a steady state at all. The run's own reading is kept beside it.
    $postRecoveryDelaySeconds = if ($null -ne $events.postRecoveryWindow -and $null -ne $events.postRecoveryWindow.preWindowDelaySeconds) { [double]$events.postRecoveryWindow.preWindowDelaySeconds } else { 10 }
    $postRecoveryMinSeconds = if ($null -ne $events.postRecoveryWindow -and $null -ne $events.postRecoveryWindow.minimumWindowSeconds) { [double]$events.postRecoveryWindow.minimumWindowSeconds } else { 20 }
    $postRecovery = [ordered]@{
        available = $false
        preWindowDelaySeconds = $postRecoveryDelaySeconds
        minimumWindowSeconds = $postRecoveryMinSeconds
        actualSeconds = $null
        sampleSpanSeconds = $null
        sampleCount = 0
        secondsOverdueAtLastSample = $null
        reason = "no backlog normalisation was observed, so there is no post-recovery window to measure"
        basis = "backlogNormalizedAt + preWindowDelaySeconds to the end of the measured load; reported only when at least minimumWindowSeconds of measured load remained. Two separate numbers: the delay before the window opens and the minimum length it must reach. Recomputed from the whole sample series, so a normalisation seen in the drain is reasoned about instead of being reported as absent"
        harnessReportedAvailable = if ($null -eq $events.postRecoveryWindow) { $null } else { $events.postRecoveryWindow.available }
        harnessReportedReason = if ($null -eq $events.postRecoveryWindow) { $null } else { $events.postRecoveryWindow.reason }
    }
    if ($null -ne $recomputedNormalizedAt -and $samples.Count -gt 0) {
        $from = $recomputedNormalizedAt.AddSeconds($postRecoveryDelaySeconds)
        $remaining = $samples[-1].at - $from
        $window = @($samples | Where-Object { $_.at -ge $from })
        $postRecovery.actualSeconds = [math]::Round($remaining.TotalSeconds, 3)
        $postRecovery.sampleCount = $window.Count
        if ($window.Count -gt 0) {
            $postRecovery.sampleSpanSeconds = [math]::Round(($window[-1].at - $window[0].at).TotalSeconds, 3)
        }
        if ($remaining.TotalSeconds -lt $postRecoveryMinSeconds) {
            # A negative remainder is not a short window, it is no window: the gate opens after the last
            # sample, so the load had already stopped. Said that way rather than as "-4s remained", which
            # reads like a measurement that went wrong instead of a window that does not exist.
            $postRecovery.reason = if ($remaining.TotalSeconds -lt 0) {
                "no measured load remained at all: backlogNormalizedAt + ${postRecoveryDelaySeconds}s falls $([math]::Round(-$remaining.TotalSeconds, 3))s after the last sample, so no part of the measured load is available as a post-recovery steady state"
            } else {
                "only $([math]::Round($remaining.TotalSeconds, 3))s of measured load remained after backlogNormalizedAt + ${postRecoveryDelaySeconds}s, below the ${postRecoveryMinSeconds}s floor; a shorter window is not a steady state"
            }
        } else {
            $postRecovery.available = $true
            $postRecovery.reason = "at least ${postRecoveryMinSeconds}s of measured load remained after backlogNormalizedAt + ${postRecoveryDelaySeconds}s"
        }
    }
    if ($postRecovery.available) {
        $from = $recomputedNormalizedAt.AddSeconds($postRecoveryDelaySeconds)
        $window = Get-SubmissionWindow $LatencyIndexed $from $null
        $cohortsFault["post-recovery-steady"] = Get-CohortLatency $window
        $cohortsFault["post-recovery-steady"]["window"] = [ordered]@{
            from = $from.ToString("o")
            to = if ($samples.Count -gt 0) { $samples[-1].at.ToString("o") } else { $null }
            preWindowDelaySeconds = $postRecoveryDelaySeconds
            minimumWindowSeconds = $postRecoveryMinSeconds
            actualSeconds = $postRecovery.actualSeconds
            sampleSpanSeconds = $postRecovery.sampleSpanSeconds
            sampleCount = $postRecovery.sampleCount
            basis = "backlogNormalizedAt + preWindowDelaySeconds to the end of the measured load; reported only when at least minimumWindowSeconds of measured load remained. Two separate numbers: the delay before the window opens and the minimum length it must reach"
        }
    } else {
        $cohortsFault["post-recovery-steady"] = [ordered]@{ available = $false; reason = $postRecovery.reason }
    }

    # The claimed_at age at the kill, recomputed here from the preserved rows so it does not depend on
    # the harness having had time to compute it before the kill.
    $claimAges = [ordered]@{ count = $null; p50 = $null; p95 = $null; max = $null; basis = "killed-node-claims.csv holds every node's PUBLISHING rows and the outbox has no claimed_by column, so this describes the cluster-wide claimed-unfinished upper bound, not the killed node's own claims" }
    $claimsPath = Join-Path $runPath "killed-node-claims.csv"
    $killSnapshotPath = Join-Path $runPath "kill-snapshot.json"
    if ((Test-Path $claimsPath) -and (Test-Path $killSnapshotPath)) {
        $killDoc = Get-Content $killSnapshotPath -Raw | ConvertFrom-Json
        $dbNow = ConvertTo-UtcNaiveOrNull $killDoc.dbNow
        if ($null -ne $dbNow) {
            $ages = @()
            foreach ($row in @(Import-Csv $claimsPath)) {
                $claimedAt = ConvertTo-UtcNaiveOrNull $row.claimedAt
                if ($null -eq $claimedAt) { continue }
                $age = ($dbNow - $claimedAt).TotalSeconds
                if ($age -ge 0) { $ages += $age }
            }
            $claimAges.count = $ages.Count
            $claimAges.p50 = Get-Percentile $ages 0.50
            $claimAges.p95 = Get-Percentile $ages 0.95
            $claimAges.max = if ($ages.Count -gt 0) { [math]::Round(($ages | Measure-Object -Maximum).Maximum, 3) } else { $null }
            $claimAges.dbNow = $dbNow.ToString("o")
        } else {
            $claimAges.basis = "kill-snapshot.json carries no readable dbNow, so claimed_at age at the kill is unavailable rather than zero"
        }
    } else {
        $claimAges.basis = "no kill snapshot or claim rows were preserved for this run, so claimed_at age at the kill is unavailable"
    }

    # The first result written after the fault, and the last one belonging to a reclaimed submission.
    $firstPostFaultResultAt = $null
    foreach ($indexed in $LatencyIndexed) {
        $saved = ConvertTo-UtcNaiveOrNull $indexed.row.resultSavedAt
        if ($null -eq $saved -or $null -eq $faultAt) { continue }
        if ($saved -lt $faultAt) { continue }
        if ($null -eq $firstPostFaultResultAt -or $saved -lt $firstPostFaultResultAt) { $firstPostFaultResultAt = $saved }
    }
    $lastReclaimedResultAt = $null
    $lastReclaimedScoreboardAt = $null
    foreach ($row in $reclaimed) {
        $saved = ConvertTo-UtcNaiveOrNull $row.resultSavedAt
        if ($null -ne $saved -and ($null -eq $lastReclaimedResultAt -or $saved -gt $lastReclaimedResultAt)) { $lastReclaimedResultAt = $saved }
        $applied = ConvertTo-UtcNaiveOrNull $row.scoreboardAppliedAt
        if ($null -ne $applied -and ($null -eq $lastReclaimedScoreboardAt -or $applied -gt $lastReclaimedScoreboardAt)) { $lastReclaimedScoreboardAt = $applied }
    }

    $anchorSpecs = @(
        [pscustomobject]@{ name="measurementStartedAt"; value=(ConvertTo-DateTimeOrNull $events.measurementStartedAt); source="harness"; basis="start of the measured window, derived from the JVM trace segment start plus the steady guard" }
        [pscustomobject]@{ name="faultScheduledAt"; value=(ConvertTo-DateTimeOrNull $events.faultScheduledAt); source="harness"; basis="the instant the trigger window opened (measurementStartedAt + minSteadySeconds). It is not a kill deadline: the kill waits for observed work" }
        [pscustomobject]@{ name="faultInjectedAt"; value=$faultAt; source="harness"; basis="the instant docker compose kill returned for the target node" }
        [pscustomobject]@{ name="restartScheduledAt"; value=(ConvertTo-DateTimeOrNull $events.restartScheduledAt); source="harness"; basis="faultInjectedAt + downDurationSeconds, computed at the injection so a slow pre-kill snapshot moves both ends together" }
        [pscustomobject]@{ name="restartRequestedAt"; value=$restartRequestedAt; source="harness"; basis="recorded before docker compose start was invoked" }
        [pscustomobject]@{ name="containerRunningAt"; value=(ConvertTo-DateTimeOrNull $events.containerRunningAt); source="harness"; basis="docker inspect read State.Running as true" }
        [pscustomobject]@{ name="nodeReadyAt"; value=$nodeReadyAt; source="harness"; basis="container running AND /actuator/health/readiness UP AND /actuator/prometheus scrapable AND contest_judge_claim_calls_total observed to advance" }
        [pscustomobject]@{ name="firstStaleObservedAt"; value=(ConvertTo-DateTimeOrNull $events.firstStaleReclaimObservedAt); source="harness"; basis="durable SUM(attempts - 1) above its pre-fault value; polled about once a second, so this is bounded by the poll interval and is not the instant the lease was acquired" }
        [pscustomobject]@{ name="firstPostFaultResultAt"; value=$firstPostFaultResultAt; source="analyzer"; basis="minimum result_saved_at at or after faultInjectedAt, read from latency.csv" }
        [pscustomobject]@{ name="throughputRecoveredAt"; value=$recomputedRecoveredAt; source="analyzer"; basis="first 5s rolling result-RPS window at or after nodeReadyAt whose value and the next two consecutive windows are all at or above 90% of the pre-fault result RPS" }
        [pscustomobject]@{ name="backlogNormalizedAt"; value=$recomputedNormalizedAt; source="analyzer"; basis="the first sample after which judge backlog and scoreboard pending each stayed at or below their own pre-fault p95 for 5s of wall clock, with no gap inside the holding streak wider than 2.5s; the later of the two" }
        [pscustomobject]@{ name="lastReclaimedSubmissionResultAt"; value=$lastReclaimedResultAt; source="analyzer"; basis="maximum result_saved_at over submissions whose outbox attempts exceeded 1 after the fault" }
        [pscustomobject]@{ name="lastReclaimedSubmissionScoreboardAt"; value=$lastReclaimedScoreboardAt; source="analyzer"; basis="maximum scoreboard_applied_at over the same reclaimed submissions" }
        [pscustomobject]@{ name="drainCompletedAt"; value=(ConvertTo-DateTimeOrNull $events.drainEndedAt); source="harness"; basis="the drain loop's backlog reading reached zero" }
    )
    $anchors = [ordered]@{}
    foreach ($spec in $anchorSpecs) {
        $anchors[$spec.name] = [ordered]@{
            value = if ($null -eq $spec.value) { $null } else { $spec.value.ToString("o") }
            source = $spec.source
            secondsAfterFault = if ($null -eq $spec.value -or $null -eq $faultAt) { $null } else { [math]::Round(($spec.value - $faultAt).TotalSeconds, 3) }
            secondsAfterRestartRequested = if ($null -eq $spec.value -or $null -eq $restartRequestedAt) { $null } else { [math]::Round(($spec.value - $restartRequestedAt).TotalSeconds, 3) }
            basis = $spec.basis
        }
    }

    $recoveryTimes = [ordered]@{
        T_staleSeconds = $anchors["firstStaleObservedAt"].secondsAfterFault
        T_staleBasis = "firstStaleObservedAt - faultInjectedAt. T_stale is NOT assumed to equal the configured claim timeout: the lease expires on claimed_at + timeout, and claimed_at is earlier than the injection by however long the row had been held, so the two can differ in either direction"
        T_restartRequestedSeconds = $anchors["restartRequestedAt"].secondsAfterFault
        T_containerRunningSeconds = $anchors["containerRunningAt"].secondsAfterRestartRequested
        T_nodeReadySeconds = $anchors["nodeReadyAt"].secondsAfterRestartRequested
        T_throughputRecoverySeconds = $anchors["throughputRecoveredAt"].secondsAfterFault
        T_backlogNormalizationSeconds = $anchors["backlogNormalizedAt"].secondsAfterFault
        T_lastReclaimedResultSeconds = $anchors["lastReclaimedSubmissionResultAt"].secondsAfterFault
        T_lastReclaimedScoreboardSeconds = $anchors["lastReclaimedSubmissionScoreboardAt"].secondsAfterFault
        downDurationSeconds = if ($null -eq $faultAt -or $null -eq $restartRequestedAt) { $null } else { [math]::Round(($restartRequestedAt - $faultAt).TotalSeconds, 3) }
        downDurationConfiguredSeconds = $parameters.downDurationSeconds
        drainSeconds = $events.drainSeconds
    }

    return [ordered]@{
        source = [ordered]@{
            mode = "fault-recovery"
            killedNode = $parameters.killedNode
            signal = "SIGKILL"
            downDurationBasis = "restartRequestedAt - faultInjectedAt"
            sampleSource = if (Test-Path (Join-Path $runPath "recovery-samples.csv")) { "recovery-samples.csv" } else { "timeseries.csv (the fault phase wrote no recovery-samples.csv)" }
            sampleCount = $samples.Count
            # The recovery series stops with the fault phase; the drain samples appended to it are what let
            # a normalisation that lands after the load stopped be seen at all. Counted rather than assumed,
            # because a run whose answer rests on drain samples reads differently from one that does not.
            drainSamplesAppended = $recoverySamplesAppended
            sampleSourceBasis = "recovery-samples.csv is the fault phase's own denser series and is preferred; the drain samples the sampler wrote to timeseries.csv after that series ended are appended, and the count is reported as drainSamplesAppended"
        }
        trigger = $verification.faultRecovery.trigger
        runValidForRecovery = $verification.faultRecovery.runValidForRecovery
        faultNotInjectedWithActiveWork = $events.faultNotInjectedWithActiveWork
        # The harness derived this during the run from the series it had then, which stops with the fault
        # phase and therefore cannot contain a normalisation that landed in the drain. The value reported
        # here is the analyzer's recomputation over the whole series; the harness's own reading is kept
        # beside it so a difference is visible instead of being resolved by preferring one of them.
        recoveryTimeout = ($null -eq $recomputedNormalizedAt)
        recoveryTimeoutBasis = "true when no backlog normalisation was found within the measured load and the drain that follows it; recomputed here from the whole sample series rather than taken from the run, because the run's own reading cannot see the drain"
        harnessRecoveryTimeout = $events.recoveryTimeout
        anchors = $anchors
        recoveryTimes = $recoveryTimes
        preFault = [ordered]@{
            windowFrom = if ($null -eq $baselineFrom) { $null } else { $baselineFrom.ToString("o") }
            windowTo = if ($null -eq $baselineTo) { $null } else { $baselineTo.ToString("o") }
            sampleCount = $baseline.Count
            judgeBacklogP95 = $judgeP95
            judgeBacklogSamples = $judgeValues.Count
            scoreboardPendingP95 = $scoreboardP95
            scoreboardPendingSamples = $scoreboardValues.Count
            resultRps = $preFaultResultRps
            basis = "faultInjectedAt - 30s to faultInjectedAt - 5s, the same window for the throughput baseline and both backlog baselines; the excluded 5s tail keeps arrivals that landed while the trigger was being polled out of the baseline"
        }
        normalization = [ordered]@{
            judgeBacklogNormalizedAt = if ($null -eq $judgeNormalizedAt) { $null } else { $judgeNormalizedAt.ToString("o") }
            scoreboardBacklogNormalizedAt = if ($null -eq $scoreboardNormalizedAt) { $null } else { $scoreboardNormalizedAt.ToString("o") }
            backlogNormalizedAt = if ($null -eq $recomputedNormalizedAt) { $null } else { $recomputedNormalizedAt.ToString("o") }
            earliestNormalizedAt = if ($null -eq $earliestNormalizedAt) { $null } else { $earliestNormalizedAt.ToString("o") }
            earliestNormalizedBasis = "the same search run from faultInjectedAt instead, so it can precede nodeReadyAt: the surviving node alone can drain the backlog while the killed node is still down"
            searchFromAt = if ($null -eq $searchFromAt) { $null } else { $searchFromAt.ToString("o") }
            sustainSeconds = $sustainSeconds
            sustainSpanSeconds = $gated.sustainSpanSeconds
            maxSampleGapSeconds = $gated.maxSampleGapSeconds
            sustainSampleCount = $gated.sustainSampleCount
            maxSampleGapLimitSeconds = $maxSampleGapSeconds
            judgeSecondsAfterFault = if ($null -eq $judgeNormalizedAt -or $null -eq $faultAt) { $null } else { [math]::Round(($judgeNormalizedAt - $faultAt).TotalSeconds, 3) }
            scoreboardSecondsAfterFault = if ($null -eq $scoreboardNormalizedAt -or $null -eq $faultAt) { $null } else { [math]::Round(($scoreboardNormalizedAt - $faultAt).TotalSeconds, 3) }
            harnessReportedAt = $events.backlogNormalizedAt
            harnessAndAnalyzerAgree = ($events.backlogNormalizedAt -eq $(if ($null -eq $recomputedNormalizedAt) { $null } else { $recomputedNormalizedAt.ToString("o") }))
            basis = "the first sample after which each backlog stayed at or below its own pre-fault p95 for 5s of wall clock, searched from max(faultInjectedAt, nodeReadyAt); the streak is grown until it covers 5s with every sample at or below the baseline and no gap inside it wider than ${maxSampleGapSeconds}s, so a well-observed 1s series satisfies it and a sampling hole does not. The two are reported separately and the combined instant is the later of them. A sample whose count did not arrive breaks the streak instead of counting as zero"
        }
        throughput = [ordered]@{
            preFaultResultRps = $preFaultResultRps
            thresholdRps = if ($null -eq $preFaultResultRps) { $null } else { [math]::Round(0.90 * $preFaultResultRps, 6) }
            ratio = 0.90
            windowSeconds = 5
            maxWindowSpanSeconds = $maxWindowSpanSeconds
            consecutiveWindows = 3
            rollingWindowCount = $rolling.Count
            rollingSpanMaxSeconds = $rollingSpanMaxSeconds
            recoveredAt = if ($null -eq $recomputedRecoveredAt) { $null } else { $recomputedRecoveredAt.ToString("o") }
            secondsAfterFault = if ($null -eq $recomputedRecoveredAt -or $null -eq $faultAt) { $null } else { [math]::Round(($recomputedRecoveredAt - $faultAt).TotalSeconds, 3) }
            secondsAfterNodeReady = if ($null -eq $recomputedRecoveredAt -or $null -eq $nodeReadyAt) { $null } else { [math]::Round(($recomputedRecoveredAt - $nodeReadyAt).TotalSeconds, 3) }
            harnessReportedAt = $events.throughputRecoveredAt
            harnessAndAnalyzerAgree = ($events.throughputRecoveredAt -eq $(if ($null -eq $recomputedRecoveredAt) { $null } else { $recomputedRecoveredAt.ToString("o") }))
            basis = "5s rolling result RPS from the cumulative results column, each window spanning between 5s and ${maxWindowSpanSeconds}s so a sampling hole cannot pass as a rate, at or above 90% of the pre-fault result RPS for 3 consecutive windows, at or after max(faultInjectedAt, nodeReadyAt)"
        }
        postRecoveryWindow = $postRecovery
        claimedUnfinishedAtKill = [ordered]@{
            clusterWideUpperBound = $verification.faultRecovery.killSnapshot.clusterWideClaimedUnfinishedUpperBound
            attributionExact = $verification.faultRecovery.reclaimAccounting.killedNodeClaimAttributionExact
            ageSecondsAtKill = $claimAges
            strandedShareOfMaxInFlight = Get-RatioOrNull $verification.faultRecovery.killSnapshot.clusterWideClaimedUnfinishedUpperBound ([int]$parameters.mysqlMaxInFlightPerNode * 2)
            basis = "the outbox has no claimed_by column, so this is a cluster-wide claimed-unfinished upper bound over every node's PUBLISHING rows at kill time and is never reported as the killed node's active claims"
        }
        reclaimAccounting = [ordered]@{
            reclaimedRowsAfterFault = $reclaimed.Count
            reclaimedRowsHarnessEstimate = $verification.faultRecovery.reclaimAccounting.reclaimedRowsAfterFault
            label = "attempts > 1 = recovery re-claims after the lease expired, NOT concurrent duplicate CPU execution"
            sigkillCounterLoss = $verification.faultRecovery.reclaimAccounting.sigkillCounterLoss
            followUpCandidate = $verification.faultRecovery.reclaimAccounting.followUpCandidate
        }
        cohorts = $cohortsFault
        unavailable = [object[]]$unavailableFault
    }
}

# --- staircase helpers, used only when stages.json exists -----------------------------------------

function Get-EpochMillis {
    param([string]$UtcNaive)
    if ([string]::IsNullOrWhiteSpace($UtcNaive)) { return $null }
    $parsed = [datetimeoffset]::MinValue
    # Submitted timestamps are UTC without an offset, the same convention Export-Latencies uses.
    if (-not [datetimeoffset]::TryParse($UtcNaive + "Z", [ref]$parsed)) { return $null }
    return $parsed.ToUnixTimeMilliseconds()
}

# A snapshot the sampler could not take is written as a one-line marker rather than an empty file.
# Reading it as an empty scrape would turn the next delta into the counter's lifetime total, so the
# two endpoints are checked before any subtraction happens.
function Test-SnapshotReadable {
    param([string]$Label)
    foreach ($node in @("judge-1", "judge-2")) {
        $path = Join-Path $runPath "metrics\$Label-$node.prom"
        if (-not (Test-Path $path)) { return $false }
        $firstLine = Get-Content $path -TotalCount 1 -ErrorAction SilentlyContinue
        if ($null -eq $firstLine -or $firstLine -like "# unavailable*") { return $false }
    }
    return $true
}

function Get-PromMetricSum {
    param([string]$Label, [string]$Metric, [string]$RequiredTag = "", [string]$OnlyNode = "")
    $sum = 0.0; $found = $false
    foreach ($node in @("judge-1", "judge-2")) {
        if ($OnlyNode -and $node -ne $OnlyNode) { continue }
        $path = Join-Path $runPath "metrics\$Label-$node.prom"
        if (-not (Test-Path $path)) { continue }
        foreach ($line in @(Get-Content $path)) {
            if ($line -match ("^" + [regex]::Escape($Metric) + '(?:\{([^}]*)\})?\s+([^\s]+)$')) {
                # Save the captures before another -match overwrites $Matches.
                $tags = $Matches[1]
                $rawValue = $Matches[2]
                if ($RequiredTag -and $tags -notmatch [regex]::Escape($RequiredTag)) { continue }
                $value = 0.0
                if ([double]::TryParse($rawValue, [Globalization.NumberStyles]::Float,
                        [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
                    $sum += $value; $found = $true
                }
            }
        }
    }
    # A missing or unreadable snapshot stays unavailable, never zero.
    if (-not $found) { return $null }
    return $sum
}

function Get-PromDelta {
    param([string]$StartLabel, [string]$EndLabel, [string]$Metric, [string]$RequiredTag = "")
    # Subtracted per node, not over the pooled scrape. The pooled form cannot tell a node whose
    # series is missing from a node whose value is zero, so one lost scrape would quietly turn a
    # two-node delta into a one-node one with no marker on it. Doing the arithmetic inside each node
    # makes Micrometer's late meter creation exact (absent at that node means 0 there), and leaves
    # the two genuinely undecided cases - an unreadable snapshot, and a series that was present at
    # the start but missing at the end - reported as unavailable instead of guessed.
    $total = 0.0; $found = $false
    foreach ($node in @("judge-1", "judge-2")) {
        $end = Get-PromMetricSum $EndLabel $Metric $RequiredTag $node
        $start = Get-PromMetricSum $StartLabel $Metric $RequiredTag $node
        if ($null -eq $start -and $null -eq $end) { continue }
        if ($null -eq $start) {
            # Micrometer creates the meter on first use, so it can be absent from the earlier scrape
            # *of a snapshot that was taken*. An absent snapshot is a different thing: subtracting
            # from zero there would report the counter's lifetime total as this stage's work.
            if (-not (Test-SnapshotReadable $StartLabel)) { return $null }
            $start = 0.0
        } elseif ($null -eq $end) {
            # A counter does not disappear, so a series present at the start and missing at the end
            # is a lost line, not a zero.
            return $null
        }
        $total += ($end - $start); $found = $true
    }
    if (-not $found) { return $null }
    return [math]::Round($total, 3)
}

function Get-PromNodeDelta {
    param([string]$StartLabel, [string]$EndLabel, [string]$Metric,
          [string]$Node, [string]$RequiredTag = "")
    $end = Get-PromMetricSum $EndLabel $Metric $RequiredTag $Node
    $start = Get-PromMetricSum $StartLabel $Metric $RequiredTag $Node
    if ($null -eq $start -and $null -eq $end) { return $null }
    if ($null -eq $start) {
        if (-not (Test-SnapshotReadable $StartLabel)) { return $null }
        $start = 0.0
    }
    if ($null -eq $end) { return $null }
    return [math]::Round($end - $start, 3)
}

function Get-ColumnStats {
    param([object[]]$Rows, [string]$Column)
    $values = @($Rows | ForEach-Object {
        $value = 0.0
        if ([double]::TryParse([string]$_.$Column, [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { $value }
    })
    if ($values.Count -eq 0) {
        return [ordered]@{ samples=0; first=$null; last=$null; min=$null; max=$null; average=$null; delta=$null }
    }
    return [ordered]@{
        samples = $values.Count
        first = $values[0]
        last = $values[-1]
        min = ($values | Measure-Object -Minimum).Minimum
        max = ($values | Measure-Object -Maximum).Maximum
        average = [math]::Round(($values | Measure-Object -Average).Average, 3)
        delta = [math]::Round($values[-1] - $values[0], 3)
    }
}

function Get-GrowthRate {
    param([object[]]$Series, [string]$Field)
    if ($null -eq $Series -or $Series.Count -lt 2) {
        return [ordered]@{ samples=if ($null -eq $Series) { 0 } else { $Series.Count }; seconds=$null; start=$null; end=$null; rowsPerSec=$null }
    }
    $seconds = [math]::Round(([long]$Series[-1].epochMillis - [long]$Series[0].epochMillis) / 1000.0, 3)
    $start = [double]$Series[0].$Field
    $end = [double]$Series[-1].$Field
    return [ordered]@{
        samples = $Series.Count
        seconds = $seconds
        start = $start
        end = $end
        rowsPerSec = if ($seconds -gt 0) { [math]::Round(($end - $start) / $seconds, 4) } else { $null }
    }
}

function Get-EndpointGrowthRate {
    param([object[]]$Subset, [string]$Field)
    if ($null -eq $Subset -or $Subset.Count -lt 2) { return $null }
    $seconds = ([long]$Subset[-1].epochMillis - [long]$Subset[0].epochMillis) / 1000.0
    if ($seconds -le 0) { return $null }
    return ([double]$Subset[-1].$Field - [double]$Subset[0].$Field) / $seconds
}

function Get-GrowthVerdict {
    param($Rate, [double]$Threshold)
    if ($null -eq $Rate) { return $null }
    if ($Rate -gt $Threshold) { return "overloaded" }
    return "steady"
}

# The endpoint difference is the planned decision rule, but over a 27-tick window one late sample can
# move it further than a whole stage's real drift. These companions say whether the verdict would
# survive that: a least-squares slope that uses every sample rather than two, the spread of the
# tick-to-tick differences, the largest single swing, and the same rule recomputed at half and double
# the threshold and with the first and with the last sample dropped. `stable` is the answer to "would
# a small change in the window have flipped this".
function Get-GrowthRobustness {
    param([object[]]$Series, [string]$Field, [double]$Threshold)
    $blank = [ordered]@{
        available = $false
        leastSquaresRowsPerSec = $null
        perTickStdevRows = $null
        maxSingleTickSwingRows = $null
        netRows = $null
        netRowsThatWouldExceedThreshold = $null
        thresholdUsed = $Threshold
        classificationAtHalfThreshold = $null
        classificationAtDoubleThreshold = $null
        classificationWithoutFirstSample = $null
        classificationWithoutLastSample = $null
        stable = $null
    }
    if ($null -eq $Series -or $Series.Count -lt 3) {
        $blank.reason = "fewer than 3 samples cannot separate a trend from tick noise"
        return $blank
    }
    $values = @($Series | ForEach-Object { [double]$_.$Field })
    $times = @($Series | ForEach-Object { ([long]$_.epochMillis - [long]$Series[0].epochMillis) / 1000.0 })
    $n = $values.Count
    $meanT = ($times | Measure-Object -Average).Average
    $meanY = ($values | Measure-Object -Average).Average
    $covariance = 0.0; $timeVariance = 0.0
    for ($i = 0; $i -lt $n; $i++) {
        $covariance += ($times[$i] - $meanT) * ($values[$i] - $meanY)
        $timeVariance += ($times[$i] - $meanT) * ($times[$i] - $meanT)
    }
    $diffs = @(for ($i = 1; $i -lt $n; $i++) { $values[$i] - $values[$i - 1] })
    # Measure-Object has no -StandardDeviation before PowerShell 6, so the sample deviation is
    # computed here rather than taken from a cmdlet that would silently return nothing on 5.1.
    $stdev = $null; $maxSwing = $null
    if ($diffs.Count -gt 0) {
        $maxSwing = [math]::Round((($diffs | ForEach-Object { [math]::Abs($_) }) | Measure-Object -Maximum).Maximum, 4)
        if ($diffs.Count -gt 1) {
            $meanDiff = ($diffs | Measure-Object -Average).Average
            $sumSquares = 0.0
            foreach ($diff in $diffs) { $sumSquares += ($diff - $meanDiff) * ($diff - $meanDiff) }
            $stdev = [math]::Round([math]::Sqrt($sumSquares / ($diffs.Count - 1)), 4)
        }
    }
    $spanSeconds = ([long]$Series[-1].epochMillis - [long]$Series[0].epochMillis) / 1000.0
    $variants = @(
        (Get-GrowthVerdict (Get-EndpointGrowthRate $Series $Field) ($Threshold / 2.0))
        (Get-GrowthVerdict (Get-EndpointGrowthRate $Series $Field) ($Threshold * 2.0))
        (Get-GrowthVerdict (Get-EndpointGrowthRate @($Series | Select-Object -Skip 1) $Field) $Threshold)
        (Get-GrowthVerdict (Get-EndpointGrowthRate @($Series | Select-Object -First ($n - 1)) $Field) $Threshold)
    )
    $distinct = @($variants | Where-Object { $null -ne $_ } | Sort-Object -Unique)
    return [ordered]@{
        available = $true
        leastSquaresRowsPerSec = if ($timeVariance -gt 0) { [math]::Round($covariance / $timeVariance, 4) } else { $null }
        perTickStdevRows = $stdev
        maxSingleTickSwingRows = $maxSwing
        netRows = [math]::Round($values[$n - 1] - $values[0], 3)
        netRowsThatWouldExceedThreshold = [math]::Round($Threshold * $spanSeconds, 3)
        thresholdUsed = $Threshold
        classificationAtHalfThreshold = $variants[0]
        classificationAtDoubleThreshold = $variants[1]
        classificationWithoutFirstSample = $variants[2]
        classificationWithoutLastSample = $variants[3]
        stable = ($distinct.Count -le 1)
    }
}

function Get-BucketCount {
    param($Counts, [string]$Key)
    if ($Counts.ContainsKey($Key)) { return [int]$Counts[$Key] }
    return 0
}

# Reads the submit requests out of a Gatling simulation.log. The HTTP outcome lives nowhere else:
# the stock MySQL image exposes no status counter, and the assertion report is a summary rather
# than a per-request record. Timestamps are epoch millis, so a stage window can be applied to them
# directly, and a request that never returned carries an empty end timestamp - it is still counted
# as offered, because the client did send it.
function Get-SubmitHttpRows {
    param([string]$Path)
    $rows = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path $Path)) { return $rows }
    foreach ($line in [System.IO.File]::ReadLines($Path)) {
        if (-not $line.StartsWith("REQUEST`t")) { continue }
        $p = $line.Split([char]9)
        if ($p.Count -lt 6 -or $p[2] -ne "api-contest-submit") { continue }
        $started = 0L
        if (-not [long]::TryParse($p[3], [ref]$started)) { continue }
        $status = "ok"
        if ($p[5] -ne "OK") {
            $message = if ($p.Count -ge 7) { $p[6] } else { "" }
            $code = ""
            if ($message -match "actually found (\d{3})") { $code = $Matches[1] }
            if ($code) { $status = "ko$code" }
            elseif ($message -match "ConnectException|Connection refused|connect timed out|UnknownHost|No route to host") { $status = "ko-connect" }
            else { $status = "ko-other" }
        }
        $rows.Add([pscustomobject]@{ startMillis = $started; status = $status })
    }
    return $rows
}

function Get-HttpWindowSummary {
    param([object[]]$HttpRows, [long]$FromMillis, [long]$ToMillis, [double]$WindowSeconds)
    $counts = @{}
    foreach ($row in @($HttpRows | Where-Object { $_.startMillis -ge $FromMillis -and $_.startMillis -lt $ToMillis })) {
        $counts[$row.status] = 1 + (Get-BucketCount $counts $row.status)
    }
    $otherKo = 0
    foreach ($key in @($counts.Keys)) {
        if ($key -like "ko*" -and @("ko429", "ko503", "ko500", "ko-connect") -notcontains $key) {
            $otherKo += (Get-BucketCount $counts $key)
        }
    }
    $ok = Get-BucketCount $counts "ok"
    $ko429 = Get-BucketCount $counts "ko429"
    $ko503 = Get-BucketCount $counts "ko503"
    $ko500 = Get-BucketCount $counts "ko500"
    $koConnect = Get-BucketCount $counts "ko-connect"
    $offered = $ok + $ko429 + $ko503 + $ko500 + $koConnect + $otherKo
    return [ordered]@{
        offered = $offered
        ok = $ok
        ko429 = $ko429
        ko503 = $ko503
        ko500 = $ko500
        koConnect = $koConnect
        koOther = $otherKo
        successPercent = if ($offered -gt 0) { [math]::Round(100.0 * $ok / $offered, 3) } else { $null }
        achievedOkRps = if ($WindowSeconds -gt 0) { [math]::Round($ok / $WindowSeconds, 3) } else { $null }
        offeredRps = if ($WindowSeconds -gt 0) { [math]::Round($offered / $WindowSeconds, 3) } else { $null }
    }
}

function Resolve-StageLabelAt {
    param([object[]]$Segments, [long]$AtMillis)
    foreach ($segment in $Segments) {
        if ($AtMillis -ge [long]$segment.startMillis -and $AtMillis -lt [long]$segment.endMillis) {
            if ($segment.kind -eq "hold") {
                return $(if ($segment.isWarmup) { "warmup" } else { "stage-$($segment.stageIndex)" })
            }
            return "transition-$($segment.stageIndex)"
        }
    }
    return "outside-plan"
}

$parameters = Get-Content $parametersPath -Raw | ConvertFrom-Json
$events = Get-Content $eventsPath -Raw | ConvertFrom-Json
$verification = Get-Content $verificationPath -Raw | ConvertFrom-Json
$rows = if (Test-Path $latencyPath) { @(Import-Csv $latencyPath) } else { @() }
$cohortNames = @("all", "pre-fault-normal", "fault-window", "killed-node-claimed", "post-fault-arrivals")
$cohorts = [ordered]@{}
foreach ($cohortName in $cohortNames) {
    if ($cohortName -eq "killed-node-claimed" -and -not $verification.cohortAvailability.killedNodeClaimed) {
        $cohorts[$cohortName] = [ordered]@{ available=$false; reason="claim owner is not stored; all-active snapshot is only an upper bound" }
    } else {
        $selected = if ($cohortName -eq "all") { $rows } else { @($rows | Where-Object { $_.cohorts -split ';' -contains $cohortName }) }
        $cohorts[$cohortName] = Get-LatencySummary $selected
    }
}

$capacity = [ordered]@{}
$capacityPath = Join-Path $runPath "capacity.csv"
if (Test-Path $capacityPath) {
    foreach ($node in @("judge-1", "judge-2")) {
        $nodeRows = @(Import-Csv $capacityPath | Where-Object node -eq $node)
        $capacity[$node] = [ordered]@{}
        foreach ($metric in @("running", "localWaiting", "reserved")) {
            $values = @($nodeRows | ForEach-Object { if ([string]$_.$metric -ne "") { [double]$_.$metric } })
            $capacity[$node][$metric] = [ordered]@{
                samples=$values.Count
                max=if ($values.Count) { ($values | Measure-Object -Maximum).Maximum } else { $null }
                average=if ($values.Count) { [math]::Round(($values | Measure-Object -Average).Average, 3) } else { $null }
            }
        }
    }
}

$backlogRecoverySeconds = $null
$backlogPath = Join-Path $runPath "backlog.csv"
if ((Test-Path $backlogPath) -and $events.faultInjectedAt) {
    $faultAt = [datetimeoffset]::Parse($events.faultInjectedAt)
    $recovered = Import-Csv $backlogPath | Where-Object {
        [datetimeoffset]::Parse($_.timestamp) -ge $faultAt -and [long]$_.unfinished -eq 0
    } | Select-Object -First 1
    if ($recovered) {
        $backlogRecoverySeconds = [math]::Round(([datetimeoffset]::Parse($recovered.timestamp) - $faultAt).TotalSeconds, 3)
    }
}

$firstStaleReclaimSeconds = $null
if ($events.faultInjectedAt -and $events.firstStaleReclaimObservedAt) {
    $firstStaleReclaimSeconds = [math]::Round(
        ([datetimeoffset]::Parse($events.firstStaleReclaimObservedAt) -
            [datetimeoffset]::Parse($events.faultInjectedAt)).TotalSeconds, 3)
}

# --- staircase (max-in-flight capacity) analysis ---------------------------------------------------
# Everything below runs only for a staircase run. Without stages.json the summary keeps exactly the
# shape it had before the staircase harness existed, so an older run directory still analyzes.
$staircase = $null
$duplication = $null
$faultRecovery = $null
$stagesPath = Join-Path $runPath "stages.json"
if (Test-Path $stagesPath) {
    $stagesDoc = Get-Content $stagesPath -Raw | ConvertFrom-Json
    $threshold = [double]$stagesDoc.overloadThresholdRowsPerSec
    $segments = @($stagesDoc.segments)
    $stageDefs = @($stagesDoc.stages)
    $unavailableStaircase = New-Object System.Collections.Generic.List[string]
    if ($null -eq $stagesDoc.drainSeconds) {
        $unavailableStaircase.Add("stages.json carries no drainSeconds for this run, so the run-level drain time is unavailable: the harness only records it once the backlog reaches zero")
    }

    $timeseriesPath = Join-Path $runPath "timeseries.csv"
    $timeseries = if (Test-Path $timeseriesPath) { @(Import-Csv $timeseriesPath) } else { @() }
    if ($timeseries.Count -eq 0) { $unavailableStaircase.Add("timeseries.csv is missing, so no per-stage throughput, backlog or executor series is reported") }

    $simulationLogPath = Join-Path $runPath "gatling-simulation.log"
    $httpRows = Get-SubmitHttpRows $simulationLogPath
    $httpAvailable = Test-Path $simulationLogPath
    if (-not $httpAvailable) { $unavailableStaircase.Add("gatling-simulation.log is missing, so per-stage HTTP outcomes and 429/503 attribution are unavailable") }

    $reclaimsPath = Join-Path $runPath "stale-reclaims.csv"
    $reclaimMillis = if (Test-Path $reclaimsPath) {
        @(Import-Csv $reclaimsPath | ForEach-Object { Get-EpochMillis ([string]$_.timestamp) } | Where-Object { $null -ne $_ })
    } else { @() }

    # Parsed once: a per-stage window would otherwise re-parse tens of thousands of timestamps.
    $latencyIndexed = @($rows | ForEach-Object {
        [pscustomobject]@{ millis = (Get-EpochMillis ([string]$_.submittedAt)); row = $_ }
    })

    # http-1s.csv is the raw material for the 429 question: it keeps the offered rate next to the
    # refusals, so a stage can be judged against the limiter rather than by its latency alone.
    if ($httpAvailable) {
        $perSecond = @{}
        foreach ($row in $httpRows) {
            $second = [long][math]::Floor($row.startMillis / 1000)
            if (-not $perSecond.ContainsKey($second)) { $perSecond[$second] = @{} }
            $perSecond[$second][$row.status] = 1 + (Get-BucketCount $perSecond[$second] $row.status)
        }
        $secondRows = foreach ($second in (@($perSecond.Keys) | Sort-Object)) {
            $bucket = $perSecond[$second]
            $otherKo = 0
            foreach ($key in @($bucket.Keys)) {
                if ($key -like "ko*" -and @("ko429", "ko503", "ko500", "ko-connect") -notcontains $key) {
                    $otherKo += (Get-BucketCount $bucket $key)
                }
            }
            [pscustomobject]@{
                epochSecond = $second
                timestampUtc = [datetimeoffset]::FromUnixTimeSeconds($second).ToString("o")
                stageLabel = Resolve-StageLabelAt $segments ($second * 1000)
                offered = (Get-BucketCount $bucket "ok") + (Get-BucketCount $bucket "ko429") + (Get-BucketCount $bucket "ko503") + (Get-BucketCount $bucket "ko500") + (Get-BucketCount $bucket "ko-connect") + $otherKo
                ok = Get-BucketCount $bucket "ok"
                ko429 = Get-BucketCount $bucket "ko429"
                ko503 = Get-BucketCount $bucket "ko503"
                ko500 = Get-BucketCount $bucket "ko500"
                koConnect = Get-BucketCount $bucket "ko-connect"
                koOther = $otherKo
            }
        }
        @($secondRows) | Export-Csv (Join-Path $runPath "http-1s.csv") -NoTypeInformation -Encoding utf8
    }

    $stageResults = New-Object System.Collections.Generic.List[object]
    # Windows this run can vouch for: the measurement windows of stages that both held steady and
    # were not refused. They are collected here because the run-level cohort is built further down
    # from an entirely different source (the cohort column of latency.csv).
    $reliableWindows = New-Object System.Collections.Generic.List[object]
    foreach ($stage in $stageDefs) {
        $mStart = [long]$stage.measurementStartMillis
        $mEnd = [long]$stage.measurementEndMillis
        $mSeconds = [math]::Round(($mEnd - $mStart) / 1000.0, 3)
        $startMillis = [long]$stage.startMillis
        $windowSeconds = [math]::Round(($stage.endMillis - $startMillis) / 1000.0, 3)
        $windowRows = @($timeseries | Where-Object { [long]$_.epochMillis -ge $mStart -and [long]$_.epochMillis -lt $mEnd })

        # The pipeline backlog is the judge outbox plus the results the scoreboard has not applied:
        # either one growing without bound is a stage that cannot hold the offered rate.
        $series = New-Object System.Collections.Generic.List[object]
        $executorSeries = New-Object System.Collections.Generic.List[object]
        foreach ($row in $windowRows) {
            $judgeValue = 0.0; $scoreboardValue = 0.0
            $hasJudge = [double]::TryParse([string]$row.unfinishedOutbox, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$judgeValue)
            $hasScoreboard = [double]::TryParse([string]$row.unappliedScoreboard, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$scoreboardValue)
            if ($hasJudge -and $hasScoreboard) {
                $series.Add([pscustomobject]@{
                    epochMillis = [long]$row.epochMillis
                    judge = $judgeValue
                    scoreboard = $scoreboardValue
                    total = $judgeValue + $scoreboardValue
                })
            }
            # A node that could not be scraped leaves an empty gauge. Summing only the reachable
            # node and calling it the total would understate the executor, so the row is dropped.
            $gauges = @{}
            $gaugesComplete = $true
            foreach ($column in @("judge1Running", "judge2Running", "judge1Queued", "judge2Queued", "judge1Reserved", "judge2Reserved")) {
                $value = 0.0
                if ([double]::TryParse([string]$row.$column, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
                    $gauges[$column] = $value
                } else { $gaugesComplete = $false }
            }
            if ($gaugesComplete) {
                $executorSeries.Add([pscustomobject]@{
                    epochMillis = [long]$row.epochMillis
                    running = $gauges["judge1Running"] + $gauges["judge2Running"]
                    queued = $gauges["judge1Queued"] + $gauges["judge2Queued"]
                    reserved = $gauges["judge1Reserved"] + $gauges["judge2Reserved"]
                })
            }
        }

        $totalGrowth = Get-GrowthRate $series "total"
        $judgeGrowth = Get-GrowthRate $series "judge"
        $scoreboardGrowth = Get-GrowthRate $series "scoreboard"
        # The window can hold rows whose backlog cells did not parse, so the sample count that the
        # classification actually rests on is the series length, not the row count. Reporting the row
        # count as "samples" while the trend came from fewer would make the reason text wrong.
        $sampleCount = $windowRows.Count
        $seriesCount = $series.Count
        $growthRobustness = Get-GrowthRobustness $series "total" $threshold

        $classification = "unknown"
        $classificationReason = if ($seriesCount -lt 10) {
            "only $seriesCount backlog samples in the measurement window ($sampleCount rows); fewer than 10 cannot separate growth from sampling noise"
        } else {
            "the measurement window holds $seriesCount usable backlog samples but no rate could be computed from them"
        }
        if ($seriesCount -ge 10 -and $null -ne $totalGrowth.rowsPerSec) {
            if ($totalGrowth.rowsPerSec -gt $threshold) {
                $classification = "overloaded"
                $classificationReason = "total pipeline backlog grew $($totalGrowth.rowsPerSec) rows/s across the measurement window, above the $threshold rows/s threshold"
            } else {
                $classification = "steady"
                $classificationReason = "total pipeline backlog changed $($totalGrowth.rowsPerSec) rows/s across the measurement window, at or below the $threshold rows/s threshold"
            }
        }

        $http = Get-HttpWindowSummary $httpRows $mStart $mEnd $mSeconds
        if (-not $httpAvailable) {
            $http = [ordered]@{ offered=$null; ok=$null; ko429=$null; ko503=$null; ko500=$null; koConnect=$null; koOther=$null; successPercent=$null; achievedOkRps=$null; offeredRps=$null }
        }
        $ko429Share = if ($null -ne $http.offered -and $http.offered -gt 0) { [math]::Round($http.ko429 / $http.offered, 4) } else { $null }
        # Pollution is any refusal, not just the limiter's 429: a 503 or a dropped connection removes
        # offered work from the stage just as effectively, and the reason text below says so.
        $refused = if ($null -eq $http.offered) { $null } else { [int]$http.ko429 + [int]$http.ko503 + [int]$http.ko500 + [int]$http.koConnect + [int]$http.koOther }
        $refusedShare = if ($null -ne $refused -and $http.offered -gt 0) { [math]::Round($refused / $http.offered, 4) } else { $null }
        $polluted = ($null -ne $refusedShare -and $refusedShare -ge $ApiRateLimitShare)
        $offeredVsTargetPercent = if ($null -ne $http.offeredRps -and $stage.targetRps -gt 0) { [math]::Round(100.0 * $http.offeredRps / $stage.targetRps, 2) } else { $null }

        # How faithfully the one-second sampler actually ran inside this window, so a stage whose
        # boundary snapshot was taken late can be recognised rather than trusted.
        $tickIntervals = @($windowRows | ForEach-Object {
            $value = 0.0
            if ([double]::TryParse([string]$_.sampleIntervalMs, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { $value }
        })
        $tickHonesty = [ordered]@{
            ticks = $sampleCount
            maxIntervalMs = if ($tickIntervals.Count -gt 0) { [math]::Round((($tickIntervals | Measure-Object -Maximum).Maximum), 3) } else { $null }
            meanIntervalMs = if ($tickIntervals.Count -gt 0) { [math]::Round((($tickIntervals | Measure-Object -Average).Average), 3) } else { $null }
            ticksSlowerThan1500Ms = @($tickIntervals | Where-Object { $_ -gt 1500 }).Count
            maxBoundaryLagMs = (@($stage.prometheusStartLagMs, $stage.prometheusMeasurementStartLagMs, $stage.prometheusEndLagMs) |
                Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ } |
                Measure-Object -Maximum).Maximum
        }

        $stageLatencyRows = @($latencyIndexed | Where-Object {
            $null -ne $_.millis -and $_.millis -ge $mStart -and $_.millis -lt $mEnd } | ForEach-Object { $_.row })
        $latency = Get-LatencySummary $stageLatencyRows
        $latencyByClass = Get-LatencyClassSummary $stageLatencyRows

        # The guard-adjusted scrape was added for this experiment. Existing counters retain their
        # hold-wide start label for backwards compatibility. Timer deltas below are completion-attributed:
        # an invocation contributes its full duration when its timer is recorded between the two scrapes.
        # They therefore are not an integral of worker occupancy over the 60-second interval.
        $classMetricStartLabel = if ($stage.prometheusMeasurementStartLabel) {
            [string]$stage.prometheusMeasurementStartLabel
        } else { [string]$stage.prometheusStartLabel }
        $judgeWorkByClass = [ordered]@{}
        foreach ($latencyClass in @("fast", "slow")) {
            $tag = 'latency_class="' + $latencyClass + '"'
            $classInvocations = Get-PromDelta $classMetricStartLabel $stage.prometheusEndLabel `
                "contest_judge_latency_class_invocations_total" $tag
            $classDurationSeconds = Get-PromDelta $classMetricStartLabel $stage.prometheusEndLabel `
                "contest_judge_latency_class_duration_seconds_sum" $tag
            $judgeWorkByClass[$latencyClass] = [ordered]@{
                invocations = $classInvocations
                completionAttributedDurationSeconds = $classDurationSeconds
                completionAttributedDurationMillis = if ($null -eq $classDurationSeconds) { $null } else { [math]::Round(1000.0 * $classDurationSeconds, 3) }
            }
        }
        $completionAttributedJudgeSeconds = if (@($judgeWorkByClass.fast.completionAttributedDurationSeconds, $judgeWorkByClass.slow.completionAttributedDurationSeconds) -contains $null) {
            $null
        } else { [math]::Round($judgeWorkByClass.fast.completionAttributedDurationSeconds + $judgeWorkByClass.slow.completionAttributedDurationSeconds, 6) }
        $nominalWorkerSeconds = [math]::Round($mSeconds * 2 * [int]$parameters.workerCountPerNode, 3)
        $judgeWorkByClass["total"] = [ordered]@{
            completionAttributedDurationSeconds = $completionAttributedJudgeSeconds
            nominalWorkerSeconds = $nominalWorkerSeconds
            completionAttributedDurationPerNominalWorkerSecond = if ($null -eq $completionAttributedJudgeSeconds -or $nominalWorkerSeconds -le 0) { $null } else { [math]::Round($completionAttributedJudgeSeconds / $nominalWorkerSeconds, 6) }
            windowBasis = "timer recordings completed between guard-adjusted measurement-start and hold-end snapshots; each completed invocation contributes its full duration, so invocations crossing either boundary are censored or asymmetrically attributed. This is not a time integral of worker occupancy or exact utilization."
        }

        $claimByNode = [ordered]@{}
        foreach ($node in @("judge-1", "judge-2")) {
            $nodeClasses = [ordered]@{}
            foreach ($latencyClass in @("fast", "slow")) {
                $tag = 'latency_class="' + $latencyClass + '"'
                $nodeClasses[$latencyClass] = [ordered]@{
                    invocations = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel `
                        "contest_judge_latency_class_invocations_total" $node $tag
                    durationSeconds = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel `
                        "contest_judge_latency_class_duration_seconds_sum" $node $tag
                }
            }
            $claimByNode[$node] = [ordered]@{
                claimCalls = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_claim_calls_total" $node
                claimedRows = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_claim_rows_total" $node
                staleClaims = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_claim_stale_total" $node
                staleTokenCompletions = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_completion_total" $node 'outcome="stale"'
                storedResultRepublishes = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_stored_result_republish_total" $node
                failedExecutions = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_completion_total" $node 'outcome="failure"'
                executorRejected = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_executor_rejections_total" $node
                actualJudgeInvocations = Get-PromNodeDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_invocations_total" $node
                latencyClasses = $nodeClasses
            }
        }

        $reclaimInStage = @($reclaimMillis | Where-Object { $_ -ge $mStart -and $_ -lt $mEnd }).Count
        $staleCompletions = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_completion_total" 'outcome="stale"'
        # Priced on the duplicate count, not on stale: a reclaimed row's original execution is fenced
        # too, so stale carries one non-duplicate completion per reclaimed row. Same derivation as the
        # run-level pair, from this window's own counters.
        $stageRepublishes = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_stored_result_republish_total"
        $stageFailures = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_completion_total" 'outcome="failure"'
        $stageDuplicateJudgements = if (@($staleCompletions, $stageRepublishes, $stageFailures) -contains $null) { $null } else {
            $staleCompletions - $stageRepublishes - $stageFailures
        }
        $duplicateLowerMs = if ($null -eq $stageDuplicateJudgements) { $null } else { [math]::Round($stageDuplicateJudgements * $DuplicateJudgeMillisFloor, 3) }
        $duplicateUpperMs = if ($null -eq $stageDuplicateJudgements) { $null } else { [math]::Round($stageDuplicateJudgements * $DuplicateJudgeMillisCeiling, 3) }

        $accepted = Get-ColumnStats $windowRows "acceptedTotal"
        $results = Get-ColumnStats $windowRows "resultsTotal"
        $scoreboardApplied = Get-ColumnStats $windowRows "scoreboardTotal"

        # The claim counters are read once here because two sections quote them: the claim cost table
        # and the mechanism table below. Reading them twice would double the scrape parsing and let
        # the two tables disagree if a read ever failed on one path only.
        $claimCallsDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_claim_calls_total"
        $claimRowsDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_claim_rows_total"
        $invocationDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_invocations_total"
        $durationSumDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_duration_seconds_sum"
        $durationCountDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_duration_seconds_count"

        # Why the achieved rate falls short of workers / mean-service-time is not visible in the
        # throughput number alone. These three do make it visible: the service time the JVM actually
        # timed, the occupancy that the reserved gauge implies (Little's law, reserved = rate x time),
        # and the gap between them. That gap mixes local queue wait with anything a claim spends
        # outside the timed region, so a wide gap at mif above the worker count is the queue showing
        # up, while a wide gap at mif at or below the worker count points at claim/poll overhead
        # instead - the two runs are compared on exactly that split.
        $timedJudgeMeanMs = if ($null -ne $durationSumDelta -and $null -ne $durationCountDelta -and $durationCountDelta -gt 0) {
            [math]::Round(1000.0 * $durationSumDelta / $durationCountDelta, 3)
        } else { $null }
        $reservedMean = (Get-ColumnStats $executorSeries "reserved").average
        $runningMean = (Get-ColumnStats $executorSeries "running").average
        $achievedResultPerSecond = if ($mSeconds -gt 0 -and $null -ne $results.delta) { [math]::Round($results.delta / $mSeconds, 3) } else { $null }
        $impliedOccupancyMs = if ($null -ne $reservedMean -and $null -ne $achievedResultPerSecond -and $achievedResultPerSecond -gt 0) {
            [math]::Round(1000.0 * $reservedMean / $achievedResultPerSecond, 3)
        } else { $null }
        $mechanism = [ordered]@{
            timedJudgeMeanMs = $timedJudgeMeanMs
            timedJudgeSampleCount = $durationCountDelta
            impliedOccupancyMs = $impliedOccupancyMs
            impliedOccupancyBasis = "mean reserved across both nodes divided by the achieved result rate (Little's law). reserved counts claimed-but-not-finished work, so wherever a local queue exists this value is service time plus local queue wait, not claim overhead; only the untimed gap at mif close to the worker count can be read as overhead. Unavailable when either the reserved gauge or the result delta is missing."
            untimedPerClaimMs = if ($null -ne $impliedOccupancyMs -and $null -ne $timedJudgeMeanMs) {
                [math]::Round($impliedOccupancyMs - $timedJudgeMeanMs, 3)
            } else { $null }
            untimedPerClaimBasis = "implied occupancy minus the JVM-timed mean. The two are not taken over the same window - occupancy is the mean of the one-second reserved gauge across the guarded measurement window, the timed mean is a pair of boundary scrapes spanning the whole hold - so this is an indication of the gap (local queue wait plus anything a claim spends outside the timed region), not a per-claim overhead measured directly. A value near zero, including a small negative one, means only that the two agree to within their windows."
            reservedMeanBothNodes = $reservedMean
            runningMeanBothNodes = $runningMean
            workerCountBothNodes = (2 * [int]$parameters.workerCountPerNode)
            workerUtilization = if ($null -ne $runningMean -and $parameters.workerCountPerNode -gt 0) {
                [math]::Round($runningMean / (2.0 * [int]$parameters.workerCountPerNode), 4)
            } else { $null }
            claimsPerSecond = if ($null -ne $claimCallsDelta -and $mSeconds -gt 0) { [math]::Round($claimCallsDelta / $mSeconds, 3) } else { $null }
            rowsPerClaim = if ($null -ne $claimRowsDelta -and $null -ne $claimCallsDelta -and $claimCallsDelta -gt 0) {
                [math]::Round($claimRowsDelta / $claimCallsDelta, 3)
            } else { $null }
            claimRowsPerClaimRowLimit = [int]$parameters.mysqlClaimBatchSize
            maxInFlightPerNode = [int]$parameters.mysqlMaxInFlightPerNode
        }

        # A percentile is only a service time where the pipeline kept up and nothing else was
        # refusing work. Both qualifications are listed rather than the first one that applies: a
        # stage can be overloaded *and* rate limited, and dropping one hides half the reason.
        $unusableReasons = New-Object System.Collections.Generic.List[string]
        if ($classification -eq "overloaded") { $unusableReasons.Add("backlog grew throughout the window, so p95/p99 measure queueing rather than service time") }
        if ($classification -eq "unknown") { $unusableReasons.Add($classificationReason) }
        # Criterion (c) is "no refusals", and a window with no HTTP data cannot answer it: $refusedShare
        # is then null, which is falsy, so $polluted is false and the stage could be certified - and
        # even published in the measurement-steady cohort - without the refusal check ever running.
        # A missing artifact is missing evidence, not a clean stage.
        if ($null -eq $http.offered) {
            $unusableReasons.Add("the HTTP outcomes for this window are unavailable because the run has no readable gatling-simulation.log, so the refusal check that guards service latency was never evaluated")
        } elseif ([int]$http.offered -le 0) {
            $unusableReasons.Add("no submission was offered inside this window, so the refusal check that guards service latency could not be evaluated")
        }
        if ($polluted) {
            $unusableReasons.Add("the API refused $refused of $($http.offered) submissions in this window ($($http.ko429)x429, $($http.ko503)x503, $($http.ko500)x500, $($http.koConnect) connect, $($http.koOther) other), so the latency also includes refusals rather than judge work alone")
        }
        # A steady verdict that a single late sample could have flipped is not a base to read service
        # time from, even though the planned rule called the stage steady.
        if ($classification -eq "steady" -and $growthRobustness.available -and -not $growthRobustness.stable) {
            $unusableReasons.Add("the steady verdict is not robust: the same rule gives different answers when the threshold is halved or doubled, or when the first or last sample is dropped")
        }
        if ($latency.L_total_ms.count -lt 10) { $unusableReasons.Add("only $($latency.L_total_ms.count) complete submissions were observed inside this window") }
        $reliable = ($unusableReasons.Count -eq 0)
        $reliableReason = if ($reliable) {
            "steady backlog and no API refusals, so the percentiles describe service time"
        } else { "not usable as steady-state latency: " + ($unusableReasons -join "; ") }
        if ($reliable -and -not [bool]$stage.isWarmup) {
            $reliableWindows.Add([pscustomobject]@{
                stageIndex = $stage.stageIndex
                targetRps = $stage.targetRps
                startMillis = $mStart
                endMillis = $mEnd
            })
        }

        $stageResults.Add([ordered]@{
            stageIndex = $stage.stageIndex
            label = $stage.label
            isWarmup = [bool]$stage.isWarmup
            targetRps = $stage.targetRps
            population = $stage.population
            start = $stage.start
            end = $stage.end
            windowSeconds = $windowSeconds
            measurementStart = $stage.measurementStart
            measurementEnd = $stage.measurementEnd
            measurementSeconds = $mSeconds
            traceSegmentIndex = $stage.traceSegmentIndex
            prometheusStartLabel = $stage.prometheusStartLabel
            prometheusMeasurementStartLabel = $stage.prometheusMeasurementStartLabel
            prometheusEndLabel = $stage.prometheusEndLabel
            prometheusStartLagMs = $stage.prometheusStartLagMs
            prometheusMeasurementStartLagMs = $stage.prometheusMeasurementStartLagMs
            prometheusEndLagMs = $stage.prometheusEndLagMs
            classification = $classification
            classificationReason = $classificationReason
            samples = $sampleCount
            seriesSamples = $seriesCount
            growthRobustness = $growthRobustness
            tickHonesty = $tickHonesty
            offeredVsTargetPercent = $offeredVsTargetPercent
            backlogGrowth = [ordered]@{
                judgeRowsPerSec = $judgeGrowth.rowsPerSec
                scoreboardRowsPerSec = $scoreboardGrowth.rowsPerSec
                totalRowsPerSec = $totalGrowth.rowsPerSec
                judgeStart = $judgeGrowth.start; judgeEnd = $judgeGrowth.end
                scoreboardStart = $scoreboardGrowth.start; scoreboardEnd = $scoreboardGrowth.end
                totalStart = $totalGrowth.start; totalEnd = $totalGrowth.end
                totalPeak = (Get-ColumnStats $series "total").max
                seconds = $totalGrowth.seconds
            }
            backlogByHalf = [ordered]@{
                firstHalfRowsPerSec = (Get-GrowthRate @($series | Select-Object -First ([math]::Floor($series.Count / 2))) "total").rowsPerSec
                secondHalfRowsPerSec = (Get-GrowthRate @($series | Select-Object -Skip ([math]::Floor($series.Count / 2))) "total").rowsPerSec
            }
            accepted = [ordered]@{
                start = $accepted.first; end = $accepted.last
                delta = $accepted.delta
                perSecond = if ($mSeconds -gt 0 -and $null -ne $accepted.delta) { [math]::Round($accepted.delta / $mSeconds, 3) } else { $null }
            }
            resultsCompleted = [ordered]@{
                start = $results.first; end = $results.last
                delta = $results.delta
                perSecond = if ($mSeconds -gt 0 -and $null -ne $results.delta) { [math]::Round($results.delta / $mSeconds, 3) } else { $null }
            }
            scoreboardApplied = [ordered]@{
                start = $scoreboardApplied.first; end = $scoreboardApplied.last
                delta = $scoreboardApplied.delta
                perSecond = if ($mSeconds -gt 0 -and $null -ne $scoreboardApplied.delta) { [math]::Round($scoreboardApplied.delta / $mSeconds, 3) } else { $null }
            }
            http = $http
            ko429Share = $ko429Share
            refusedShare = $refusedShare
            apiRateLimitPolluted = $polluted
            latency = $latency
            latencyByClass = $latencyByClass
            judgeWorkByLatencyClass = $judgeWorkByClass
            reliableAsSteadyStateLatency = $reliable
            reliableAsSteadyStateLatencyReason = $reliableReason
            overloadQueueingResults = ($classification -eq "overloaded")
            executor = [ordered]@{
                judge1 = [ordered]@{
                    running = Get-ColumnStats $windowRows "judge1Running"
                    queued = Get-ColumnStats $windowRows "judge1Queued"
                    reserved = Get-ColumnStats $windowRows "judge1Reserved"
                }
                judge2 = [ordered]@{
                    running = Get-ColumnStats $windowRows "judge2Running"
                    queued = Get-ColumnStats $windowRows "judge2Queued"
                    reserved = Get-ColumnStats $windowRows "judge2Reserved"
                }
                bothNodes = [ordered]@{
                    running = Get-ColumnStats $executorSeries "running"
                    queued = Get-ColumnStats $executorSeries "queued"
                    reserved = Get-ColumnStats $executorSeries "reserved"
                }
            }
            # The claim path is only bounded as designed if the reserved counter never passes the
            # configured max-in-flight, and the workers are only as busy as designed if running never
            # passes the worker count. Both are checked against the code's own limits rather than
            # against each other, so a run where a cap was exceeded is visible instead of inferred.
            executorCaps = [ordered]@{
                runningMaxBothNodes = (Get-ColumnStats $executorSeries "running").max
                runningLimitBothNodes = 2 * [int]$parameters.workerCountPerNode
                reservedMaxBothNodes = (Get-ColumnStats $executorSeries "reserved").max
                reservedLimitBothNodes = 2 * [int]$parameters.mysqlMaxInFlightPerNode
                withinConfiguredCaps = (
                    $null -ne (Get-ColumnStats $executorSeries "running").max -and
                    $null -ne (Get-ColumnStats $executorSeries "reserved").max -and
                    (Get-ColumnStats $executorSeries "running").max -le (2 * [int]$parameters.workerCountPerNode) -and
                    (Get-ColumnStats $executorSeries "reserved").max -le (2 * [int]$parameters.mysqlMaxInFlightPerNode)
                )
                basis = "both nodes' gauges summed per tick; the limits are 2 x workerCountPerNode for running and 2 x mysqlMaxInFlightPerNode for reserved, as configured in parameters.json"
            }
            mysql = [ordered]@{
                threadsConnected = Get-ColumnStats $windowRows "threadsConnected"
                threadsRunning = Get-ColumnStats $windowRows "threadsRunning"
                rowLockCurrentWaits = Get-ColumnStats $windowRows "innodbRowLockCurrentWaits"
                rowLockWaits = Get-ColumnStats $windowRows "innodbRowLockWaits"
                questions = Get-ColumnStats $windowRows "questions"
                rowLockWaitsPerSecond = if ($mSeconds -gt 0 -and $null -ne (Get-ColumnStats $windowRows "innodbRowLockWaits").delta) { [math]::Round((Get-ColumnStats $windowRows "innodbRowLockWaits").delta / $mSeconds, 4) } else { $null }
                questionsPerSecond = if ($mSeconds -gt 0 -and $null -ne (Get-ColumnStats $windowRows "questions").delta) { [math]::Round((Get-ColumnStats $windowRows "questions").delta / $mSeconds, 4) } else { $null }
            }
            claim = [ordered]@{
                windowBasis = "staleReclaimRowsInWindow counts rows whose outbox updated_at falls in the measurement window; updated_at is the row's last write, so it is when the reclaim finished, not when it happened, which is why the prometheus claim-stale counter is reported beside it. The prometheus deltas run between the hold's own start and end scrapes, which are $($windowSeconds)s apart rather than $($mSeconds)s, because snapshots are only taken at segment boundaries"
                staleReclaimRowsInWindow = $reclaimInStage
                staleReclaimRowsInWindowBasis = "a timestamp proxy, not a reclaim count: updated_at moves again when the row is finally published or failed, so a reclaim that was answered during the drain is attributed to no window and one that happened earlier can land in a later window. Read claimStaleDelta for the counter"
                claimStaleDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_claim_stale_total"
                staleCompletionDelta = $staleCompletions
                duplicateJudgementCount = $stageDuplicateJudgements
                duplicateJudgementCountBasis = "stale completions in this window minus the reclaims answered from the stored result minus the failures: the number of judgeSubmission calls beyond one per submission"
                judgeInvocationDelta = $invocationDelta
                judgeDurationSecondsDelta = $durationSumDelta
                judgeDurationCountDelta = $durationCountDelta
                claimCallsDelta = $claimCallsDelta
                claimRowsDelta = $claimRowsDelta
                executorRejectionsDelta = Get-PromDelta $stage.prometheusStartLabel $stage.prometheusEndLabel "contest_judge_executor_rejections_total"
                storedResultRepublishDelta = $stageRepublishes
                completionFailureDelta = $stageFailures
                duplicateJudgementMillisLowerBound = $duplicateLowerMs
                duplicateJudgementMillisUpperBound = $duplicateUpperMs
                duplicateJudgementBasis = "duplicate judge executions in this window priced at the deterministic profile's 50ms floor and 2000ms ceiling; the lease cannot say how long a discarded attempt actually ran"
                nodes = $claimByNode
            }
            # Drain happens once, after every stage, so there is no per-stage drain to report.
            drain = [ordered]@{ available = $false; reason = "the pipeline is drained once per run after the last stage, so drain is only defined at run level" }
            mechanism = $mechanism
        })
    }

    $measuredStages = @($stageResults | Where-Object { -not $_.isWarmup })
    $pollutedStages = @($measuredStages | Where-Object { $_.apiRateLimitPolluted })
    $unclassifiedStages = @($measuredStages | Where-Object { $_.classification -eq "unknown" })
    $classifiedStages = @($measuredStages | Where-Object { $_.classification -ne "unknown" })
    $firstOverload = @($classifiedStages | Where-Object { $_.classification -eq "overloaded" } | Select-Object -First 1)
    $overloadIndex = if ($firstOverload.Count -gt 0) { $firstOverload[0].stageIndex } else { $null }
    # "The last stage that held" means the last one *before* the first stage that did not, so an
    # oscillation later in the ladder cannot quietly promote a higher stage into the knee.
    # Built with an explicit loop rather than an if-expression: PowerShell unrolls the output of an
    # if statement, so a branch that selects exactly one stage would hand back the stage object
    # itself, whose .Count is its number of keys and whose [-1] index is a missing dictionary key.
    # That made a lone steady stage below the first overloaded stage look like no steady stage at all.
    $steadyBefore = New-Object System.Collections.Generic.List[object]
    foreach ($candidate in $classifiedStages) {
        if ($candidate.classification -ne "steady") { continue }
        if ($null -ne $overloadIndex -and $candidate.stageIndex -ge $overloadIndex) { continue }
        $steadyBefore.Add($candidate)
    }
    $lastSteady = if ($steadyBefore.Count -gt 0) { $steadyBefore[$steadyBefore.Count - 1] } else { $null }

    # A rate-limited stage is still classified: the backlog says whether the pipeline kept up, and
    # dropping the stage would replace "we could not measure this" with "this held". What the
    # refusals do invalidate is the stage as evidence about judge capacity, which is a caveat on the
    # bound rather than a deletion of it.
    $kneeUpperStage = if ($firstOverload.Count -gt 0) { $firstOverload[0] } else { $null }
    $kneeConfounded = ($null -ne $kneeUpperStage -and $kneeUpperStage.apiRateLimitPolluted)
    $kneeLower = if ($null -ne $lastSteady) { $lastSteady.targetRps } else { $null }
    $kneeUpper = if ($null -ne $kneeUpperStage) { $kneeUpperStage.targetRps } else { $null }
    $confoundNote = if ($kneeConfounded) {
        " The $kneeUpper RPS stage was also refused for $([math]::Round(100 * $kneeUpperStage.ko429Share, 3))% of its submissions with 429, so the upper bound is confounded by the API rate limiter and is not a judge-capacity reading."
    } else { "" }
    # The lower bound is only as solid as the steady verdict under it. Two ways it can be soft: the
    # verdict flips under a small change to the rule (robustness.stable = false), or the growth sits
    # above zero with little room to the threshold. Both are reported instead of being folded into a
    # bare "held up to N RPS".
    $lowerRobust = ($null -ne $lastSteady -and $lastSteady.growthRobustness.available -and $lastSteady.growthRobustness.stable)
    # The upper bound gets the same test. The first stage that did not hold is often decided by a net
    # drift smaller than a single tick's movement, which would make the "first overloaded" rung a
    # coin toss rather than the point where capacity ran out.
    $upperRobust = ($null -ne $kneeUpperStage -and $kneeUpperStage.growthRobustness.available -and $kneeUpperStage.growthRobustness.stable)
    # Rows, not rows/s: this compares the stage's net drift against the row count that would have
    # exceeded the threshold, so the unit is a count of backlog rows over the window. Only the lower
    # margin is a rate (threshold minus growth). Naming it RowsPerSec made a 3-row margin read as a
    # 3 rows/s one against a 1 rows/s threshold.
    $upperMarginRows = if ($null -ne $kneeUpperStage -and $null -ne $kneeUpperStage.growthRobustness.netRows -and $null -ne $threshold) {
        [math]::Round($kneeUpperStage.growthRobustness.netRows - $kneeUpperStage.growthRobustness.netRowsThatWouldExceedThreshold, 3)
    } else { $null }
    $upperFragilityNote = if ($null -ne $kneeUpperStage -and -not $upperRobust) {
        " The $kneeUpper RPS stage's overloaded verdict is also fragile: its net drift was $($kneeUpperStage.growthRobustness.netRows) rows against the $($kneeUpperStage.growthRobustness.netRowsThatWouldExceedThreshold) rows that would exceed the threshold, while its largest single tick moved $($kneeUpperStage.growthRobustness.maxSingleTickSwingRows) rows."
    } else { "" }
    $lowerMarginRowsPerSec = if ($null -ne $lastSteady -and $null -ne $lastSteady.backlogGrowth.totalRowsPerSec) {
        [math]::Round($threshold - $lastSteady.backlogGrowth.totalRowsPerSec, 4)
    } else { $null }
    $lowerFragilityNote = if ($null -ne $lastSteady -and -not $lowerRobust) {
        " The $kneeLower RPS stage's steady verdict is fragile: it grew $($lastSteady.backlogGrowth.totalRowsPerSec) rows/s against a $threshold rows/s threshold (margin $lowerMarginRowsPerSec), and its classification changes when the threshold is halved or doubled or when one end sample is dropped, so treat $kneeLower RPS as an upper edge rather than a proven operating point."
    } else { "" }
    # What the interval rests on: a bound whose lower end was never actually reached is weaker than
    # one bracketed by a stage that held and a stage that did not, and the count says which this is.
    # The short form exists for the cross-run table, where a three-sentence cell would bury the
    # comparison; the long form stays the one to read in this run's own summary.
    $intervalShort = if ($null -ne $kneeLower -and $null -ne $kneeUpper) {
        "($kneeLower, $kneeUpper] RPS"
    } elseif ($null -ne $kneeLower) {
        "above the ladder, >= $kneeLower RPS"
    } elseif ($null -ne $kneeUpper) {
        "below the ladder, <= $kneeUpper RPS"
    } else { "unknown" }
    $classifiedCount = $classifiedStages.Count
    $excludedCount = $unclassifiedStages.Count
    $pollutedCount = $pollutedStages.Count
    # Each note below opens with a space and closes with a period, so the clause before them has to
    # end in a period too - otherwise the sentence runs on ("...could be classified The 50 RPS stage's
    # verdict is fragile") and reads as one unpunctuated thought.
    $intervalDescription = if ($null -ne $kneeLower -and $null -ne $kneeUpper) {
        "($kneeLower, $kneeUpper] RPS, from the $classifiedCount of $($measuredStages.Count) measured stages that could be classified.$confoundNote$lowerFragilityNote$upperFragilityNote"
    } elseif ($null -ne $kneeLower) {
        "not reached: every one of the $classifiedCount classifiable measured stages held steady up to $kneeLower RPS, so capacity is above the ladder (with $excludedCount measured stages excluded as unclassifiable and $pollutedCount flagged as refused).$confoundNote$lowerFragilityNote"
    } elseif ($null -ne $kneeUpper) {
        "below the lowest measured stage: $kneeUpper RPS was already overloaded.$confoundNote$lowerFragilityNote$upperFragilityNote"
    } else { "unknown: none of the $($measuredStages.Count) measured stages could be classified" }

    $capacityKnee = [ordered]@{
        sustainedSteadyUpToRps = $kneeLower
        sustainedSteadyStageVerdictRobust = $lowerRobust
        sustainedSteadyStageMarginRowsPerSec = $lowerMarginRowsPerSec
        firstOverloadStageRps = $kneeUpper
        firstOverloadStageVerdictRobust = $upperRobust
        firstOverloadStageMarginRows = $upperMarginRows
        intervalShort = $intervalShort
        intervalDescription = $intervalDescription
        confoundedByApiRateLimit = $kneeConfounded
        basis = "measured stages only (warm-up excluded); the verdict on each stage is the planned endpoint rule (backlog grew more than $threshold rows/s over its measurement window), and a stage whose window holds fewer than 10 usable backlog samples cannot be classified"
        measuredStageCount = $measuredStages.Count
        classifiedStageCount = $classifiedCount
        unclassifiableStageCount = $excludedCount
        refusedStageCount = $pollutedCount
        stagesExcludedFromKnee = @($unclassifiedStages | ForEach-Object {
            [ordered]@{ stageIndex = $_.stageIndex; targetRps = $_.targetRps; reason = $_.classificationReason }
        })
        stagesPollutedByApiRateLimit = @($pollutedStages | ForEach-Object {
            [ordered]@{
                stageIndex = $_.stageIndex
                targetRps = $_.targetRps
                # Every refusal bucket, not just the limiter's 429: the field next to a reason that
                # lists 503/500/connect must not report the 429 share alone, or a stage refused for a
                # non-429 reason would publish an understated (or zero) share.
                refusedShare = $_.refusedShare
                ko429Share = $_.ko429Share
                ko429 = $_.http.ko429; ko503 = $_.http.ko503; ko500 = $_.http.ko500
                koConnect = $_.http.koConnect; koOther = $_.http.koOther
                reason = "$($_.http.ko429)x429, $($_.http.ko503)x503, $($_.http.ko500)x500, $($_.http.koConnect) connect and $($_.http.koOther) other refusals out of $($_.http.offered) submissions in the measurement window, at or above the $($ApiRateLimitShare * 100)% threshold"
            }
        })
        theoryReference = [ordered]@{
            note = "the 147.5ms synthetic mean over two nodes would put mif=8 near 108 RPS and mif>=16 near 217 RPS; this is a reference for explaining a difference, not a target the measurement is fitted to"
            maxInFlight8Rps = 108
            maxInFlightAtLeast16Rps = 217
        }
    }

    # A second latency read that the run can actually vouch for. `all` and `pre-fault-normal` span the
    # warm-up, every transition, the stages the pipeline could not hold and the drain, so their
    # p95/p99 mix service time with queueing. This cohort reapplies the same three latencies to just
    # the measurement windows of the stages that held steady with nothing refused.
    # ToArray rather than @(): on this PowerShell, the array subexpression operator throws
    # "Argument types do not match" when handed a generic List[object].
    $steadyWindows = $reliableWindows.ToArray()
    $cohortNames += "measurement-steady"
    # Assigned inside the branches rather than from an if-expression: PowerShell unrolls an if
    # statement's output, and an OrderedDictionary is enumerable, so the branch value would arrive as
    # a list of its entries instead of the dictionary itself.
    if ($stagesDoc.mode -eq "fault-recovery") {
        # A fault-recovery run's measured window contains the kill, so no part of it is service time.
        # The recovery cohorts A-E are the readings this run can vouch for, and a stage-level steady
        # window would either be empty or describe the queueing around the outage.
        $cohorts["measurement-steady"] = [ordered]@{
            available = $false
            reason = "this run has a fault inside its measured window, so no window in it is service time; the fault-recovery cohorts (pre-fault-steady, fault-down-arrivals, reclaimed-after-fault, post-restart-recovery, post-recovery-steady) are this run's latency readings"
        }
    } elseif ($steadyWindows.Count -eq 0) {
        $cohorts["measurement-steady"] = [ordered]@{
            available = $false
            reason = "no measured stage was both steady and free of API refusals, so no window in this run can be read as service time"
        }
    } else {
        # The row's own timestamp is read into a local before the inner filter: inside that filter
        # `$_` is a window, so `$_.millis` would silently be null and no submission would match.
        $selected = @($latencyIndexed | Where-Object {
            if ($null -eq $_.millis) { return $false }
            $atMillis = [long]$_.millis
            $inWindow = $false
            foreach ($window in $steadyWindows) {
                if ($window.startMillis -le $atMillis -and $atMillis -lt $window.endMillis) { $inWindow = $true; break }
            }
            return $inWindow
        } | ForEach-Object { $_.row })
        $summaryForWindows = Get-LatencySummary $selected
        $summaryForWindows["available"] = $true
        $summaryForWindows["windows"] = [object[]]$steadyWindows
        $summaryForWindows["windowBasis"] = "measurement windows of stages that held steady and saw no API refusals; the run-level cohorts above are not restricted this way"
        $cohorts["measurement-steady"] = $summaryForWindows
    }

    # --- duplicate claims and duplicate judging (normal-timeout runs only) -------------------------
    # Three quantities that are easy to conflate and are not the same thing:
    #   1. a durable duplicate *claim*: an outbox row whose attempts counter went above 1, i.e. the
    #      lease expired and the row was handed to a judge a second time. This is what the claim
    #      timeout controls, and it is measured from the rows themselves (attempts is durable).
    #   2. an actual duplicate *judge execution*: an execution whose result was thrown away because
    #      another attempt had already published under a newer token. In the dispatcher that is
    #      exactly completion{outcome="stale"}: judgeSubmission ran, the fenced UPDATE matched no row,
    #      and the work was discarded - so the submission was judged twice for one result.
    #   3. token-fencing evidence: the counters showing the fence worked - the stale completions
    #      above, the expired-lease rows seen at claim time, and the reclaims answered from the
    #      stored result instead of a second execution.
    # The counters also carry an accounting identity, which is checked here rather than assumed.
    # Every judge() call ends in exactly one of publish (success), fence refusal (stale) or failure
    # (failure); and a publish is either a new result row or a stored-result republish. So
    #   invocations - results = republish + failure + stale
    # and a nonzero residual means a counter path that did not record, not extra duplicates.
    if ($stagesDoc.mode -eq "normal-timeout") {
        $workCost = $verification.workCost
        $acceptedCount = $verification.counts.accepted
        $resultRows = $verification.counts.results
        $attemptRowsPath = Join-Path $runPath "claim-attempts.tsv"
        $attemptHistogram = [ordered]@{}
        $rowsAboveOne = $null
        if (Test-Path $attemptRowsPath) {
            $rowsAboveOne = 0L
            foreach ($line in @(Get-Content $attemptRowsPath | Select-Object -Skip 1)) {
                $parts = $line -split "`t"
                if ($parts.Count -lt 2) { continue }
                $attemptHistogram[$parts[0]] = [long]$parts[1]
                if ([int]$parts[0] -gt 1) {
                    $rowsAboveOne = $rowsAboveOne + [long]$parts[1]
                }
            }
        }

        $measuredStageList = @($stageResults | Where-Object { -not $_.isWarmup })
        $primaryStage = if ($measuredStageList.Count -gt 0) { $measuredStageList[0] } else { $null }

        $duplicateClaims = $workCost.duplicateClaimEstimate
        $staleExecutions = $workCost.staleTokenCompletions
        $failedExecutions = $workCost.completionFailure
        $republishes = $workCost.storedResultRepublishes
        $claimStaleRows = $workCost.staleReclaims
        $invocations = $workCost.judgeInvocations
        $invocationMinusResults = if ($null -eq $invocations -or $null -eq $resultRows) { $null } else { $invocations - $resultRows }

        # The duplicate judgement count, derived two independent ways.
        #
        # A submission is judged twice when more than one judgeSubmission call ran for it, i.e. when
        # there were more invocations than result rows. That is NOT the stale counter. A reclaimed
        # row is one whose lease expired, and its ORIGINAL execution is fenced too: the reclaim is
        # answered from the stored result and wins the publish race, so the original execution's own
        # completion matches no row. stale therefore also counts one non-duplicate execution per
        # reclaimed row. The measured relation, exact in every normal-timeout run:
        #   stale = (invocations - results) + republishes + failure
        # so pricing the duplicate count at stale alone overstates it by exactly the reclaim count.
        #
        # Both routes are reported. They agree whenever every submission produced exactly one result
        # row; a disagreement means a submission was judged but wrote no result (or wrote more than
        # one), which is a finding rather than something to average away.
        $duplicateJudgementsFromStale = if (@($staleExecutions, $republishes, $failedExecutions) -contains $null) { $null } else {
            $staleExecutions - $republishes - $failedExecutions
        }
        $duplicateJudgementsFromInvocations = if ($null -eq $invocationMinusResults -or $null -eq $failedExecutions) { $null } else {
            $invocationMinusResults - $failedExecutions
        }

        # Accounting, checked rather than assumed. Every claim ends in exactly one completion, and a
        # claim either invokes the judge or is answered from the stored result without invoking:
        #   invocations + republishes = completions
        # A completion is success, stale or failure, and exactly one success per result row publishes
        # it, so success = results:
        #   invocations + republishes = results + stale + failure
        # The residual is that difference and is 0 whenever every counter recorded. The earlier form
        # of this check subtracted the republishes from the wrong side and was therefore identically
        # -2*republishes, i.e. it could never reach 0 in any run that reclaimed a row.
        $accountedCompletions = if (@($republishes, $failedExecutions, $staleExecutions) -contains $null) { $null } else {
            $resultRows + $staleExecutions + $failedExecutions
        }
        $accountingResidual = if (@($invocations, $republishes, $resultRows, $staleExecutions, $failedExecutions) -contains $null) { $null } else {
            ($invocations + $republishes) - ($resultRows + $staleExecutions + $failedExecutions)
        }

        # Duplicate judge time, priced on the duplicate count above and not on stale. The profile is
        # deterministic, so a discarded execution cost 50ms or 2000ms and the two bounds bracket it;
        # recomputed here because the harness recorded its own bounds from the invocation excess.
        $duplicateJudgeMillisLowerBound = if ($null -eq $duplicateJudgementsFromStale) { $null } else {
            $duplicateJudgementsFromStale * $DuplicateJudgeMillisFloor
        }
        $duplicateJudgeMillisUpperBound = if ($null -eq $duplicateJudgementsFromStale) { $null } else {
            $duplicateJudgementsFromStale * $DuplicateJudgeMillisCeiling
        }

        # Exact class accounting over the complete measurement-contest scope. The start scrape is
        # taken only after the separate warm-up contest has quiesced, and the end scrape is taken
        # after drain, so every accepted measurement submission and every invocation it caused fall
        # inside the same pair of counter snapshots. This makes invocation - unique submission a
        # valid class-specific duplicate count when failed executions are zero.
        $classAccounting = [ordered]@{}
        $uniqueExpectedMillisTotal = 0.0
        $actualInvocationMillisTotal = 0.0
        $profileDuplicateMillisTotal = 0.0
        $classInvocationsTotal = 0.0
        $classDuplicatesTotal = 0.0
        $classAccountingAvailable = $true
        foreach ($latencyClass in @("fast", "slow")) {
            $classRows = @($rows | Where-Object { $_.latencyClass -eq $latencyClass })
            $uniqueClassSubmissions = $classRows.Count
            $classMillis = if ($latencyClass -eq "fast") {
                [double]$parameters.latency.baseMillis
            } else { [double]$parameters.latency.slowMillis }
            $tag = 'latency_class="' + $latencyClass + '"'
            $classInvocations = Get-PromDelta "start" "end" `
                "contest_judge_latency_class_invocations_total" $tag
            $classDurationSeconds = Get-PromDelta "start" "end" `
                "contest_judge_latency_class_duration_seconds_sum" $tag
            $classDuplicates = if ($null -eq $classInvocations -or $null -eq $failedExecutions -or [long]$failedExecutions -ne 0) {
                $null
            } else { [long]$classInvocations - $uniqueClassSubmissions }
            $uniqueExpectedMillis = $uniqueClassSubmissions * $classMillis
            $actualInvocationMillis = if ($null -eq $classDurationSeconds) { $null } else { [math]::Round(1000.0 * $classDurationSeconds, 3) }
            $profileDuplicateMillis = if ($null -eq $classDuplicates) { $null } else { $classDuplicates * $classMillis }
            if ($null -eq $classInvocations -or $null -eq $actualInvocationMillis -or $null -eq $profileDuplicateMillis) {
                $classAccountingAvailable = $false
            } else {
                $classInvocationsTotal += $classInvocations
                $actualInvocationMillisTotal += $actualInvocationMillis
                $profileDuplicateMillisTotal += $profileDuplicateMillis
                $classDuplicatesTotal += $classDuplicates
            }
            $uniqueExpectedMillisTotal += $uniqueExpectedMillis
            $classAccounting[$latencyClass] = [ordered]@{
                uniqueSubmissions = $uniqueClassSubmissions
                judgeInvocations = $classInvocations
                duplicateJudgeExecutions = $classDuplicates
                uniqueExpectedJudgeMillis = $uniqueExpectedMillis
                actualJudgeInvocationMillis = $actualInvocationMillis
                profileDuplicateJudgeMillis = $profileDuplicateMillis
                actualMinusProfileUniqueMillis = if ($null -eq $actualInvocationMillis) { $null } else { [math]::Round($actualInvocationMillis - $uniqueExpectedMillis, 3) }
            }
        }
        $classUnclassified = @($rows | Where-Object { @("fast", "slow") -notcontains $_.latencyClass }).Count
        $measurementScopeSeconds = $null
        if ($events.measurementBaselineAt -and $events.measurementEndSnapshotAt) {
            $measurementScopeSeconds = [math]::Round(
                ([datetimeoffset]::Parse($events.measurementEndSnapshotAt) -
                 [datetimeoffset]::Parse($events.measurementBaselineAt)).TotalSeconds, 3)
        }
        $availableWorkerSeconds = if ($null -eq $measurementScopeSeconds) { $null } else {
            [math]::Round($measurementScopeSeconds * 2 * [int]$parameters.workerCountPerNode, 3)
        }
        $classAccounting["total"] = [ordered]@{
            uniqueSubmissions = $rows.Count
            judgeInvocations = if ($classAccountingAvailable) { $classInvocationsTotal } else { $null }
            duplicateJudgeExecutions = if ($classAccountingAvailable) { $classDuplicatesTotal } else { $null }
            uniqueExpectedJudgeMillis = [math]::Round($uniqueExpectedMillisTotal, 3)
            actualJudgeInvocationMillis = if ($classAccountingAvailable) { [math]::Round($actualInvocationMillisTotal, 3) } else { $null }
            profileDuplicateJudgeMillis = if ($classAccountingAvailable) { [math]::Round($profileDuplicateMillisTotal, 3) } else { $null }
            duplicateJudgeMillisPerUniqueExpectedJudgeMillis = if (-not $classAccountingAvailable -or $uniqueExpectedMillisTotal -le 0) { $null } else { [math]::Round($profileDuplicateMillisTotal / $uniqueExpectedMillisTotal, 6) }
            actualJudgeSeconds = if ($classAccountingAvailable) { [math]::Round($actualInvocationMillisTotal / 1000.0, 6) } else { $null }
            measurementScopeSeconds = $measurementScopeSeconds
            availableWorkerSeconds = $availableWorkerSeconds
            actualJudgeSecondsPerAvailableWorkerSecond = if (-not $classAccountingAvailable -or $null -eq $availableWorkerSeconds -or $availableWorkerSeconds -le 0) { $null } else { [math]::Round(($actualInvocationMillisTotal / 1000.0) / $availableWorkerSeconds, 6) }
            unclassifiedSubmissions = $classUnclassified
            invocationClassesMatchGlobal = if (-not $classAccountingAvailable -or $null -eq $invocations) { $null } else { $classInvocationsTotal -eq [double]$invocations }
            duplicateClassesMatchGlobal = if (-not $classAccountingAvailable -or $null -eq $duplicateJudgementsFromInvocations) { $null } else { $classDuplicatesTotal -eq [double]$duplicateJudgementsFromInvocations }
            basis = "start snapshot after warm-up quiescence through end snapshot after drain. Actual invocation millis are Micrometer timer sums; unique expected and duplicate millis are deterministic-profile calculations, not measured duplicate durations. Class-specific duplicate counts require failedExecutions=0."
        }

        # Everything below decides whether the six-run comparison may read this run as a steady
        # state. Each criterion is stored with its own answer, so a run that fails one is flagged on
        # that criterion rather than dropped silently.
        $warmupEvidence = $verification.warmup
        $drainSucceeded = ($null -ne $stagesDoc.drainSeconds)
        $warmupQuiesced = ($null -ne $warmupEvidence -and [bool]$warmupEvidence.quiescent -and
            $null -ne $warmupEvidence.acceptedGrowthAfterBaseline -and [long]$warmupEvidence.acceptedGrowthAfterBaseline -eq 0)
        $noRefusals = ($null -ne $primaryStage -and $null -ne $primaryStage.http.offered -and -not [bool]$primaryStage.apiRateLimitPolluted)
        $withinCaps = ($null -ne $primaryStage -and [bool]$primaryStage.executorCaps.withinConfiguredCaps)
        # "Not persistently growing" is stricter than the endpoint classification: a window can end
        # at or below the threshold while its second half still drifts up, and that is a backlog on
        # its way up rather than a steady state.
        $steadyBacklog = ($null -ne $primaryStage -and $primaryStage.classification -eq "steady" -and
            $null -ne $primaryStage.backlogByHalf.secondHalfRowsPerSec -and
            [double]$primaryStage.backlogByHalf.secondHalfRowsPerSec -le [double]$threshold)
        $integrityPassed = [bool]$verification.integrity.passed
        $criteria = [ordered]@{
            integrityPassed = $integrityPassed
            uniqueEqualsAccepted = ($acceptedCount -eq $verification.counts.uniqueSubmissions)
            resultsEqualAccepted = ($resultRows -eq $acceptedCount)
            scoreboardEqualsResults = ($verification.counts.scoreboardApplied -eq $resultRows)
            noLostOrIncomplete = ($null -ne $verification.integrity.lostOrIncomplete -and [long]$verification.integrity.lostOrIncomplete -eq 0)
            noFinalResultMismatch = ($null -ne $verification.integrity.finalResultMismatch -and [long]$verification.integrity.finalResultMismatch -eq 0)
            noApiRefusals = $noRefusals
            backlogNotPersistentlyGrowing = $steadyBacklog
            drainSucceeded = $drainSucceeded
            warmupQuiescedBeforeBaseline = $warmupQuiesced
            executorWithinConfiguredCaps = $withinCaps
        }
        $failedCriteria = New-Object System.Collections.Generic.List[string]
        foreach ($name in @($criteria.Keys)) {
            if (-not [bool]$criteria[$name]) { $failedCriteria.Add($name) }
        }

        $duplication = [ordered]@{
            mode = $stagesDoc.mode
            measuredStage = if ($null -ne $primaryStage) { $primaryStage.label } else { $null }
            measurementWindowSeconds = if ($null -ne $primaryStage) { $primaryStage.measurementSeconds } else { $null }
            targetRps = if ($null -ne $primaryStage) { $primaryStage.targetRps } else { $null }
            # The denominators every rate below is taken against.
            acceptedSubmissions = $acceptedCount
            uniqueSubmissions = $verification.counts.uniqueSubmissions
            mysqlClaimTimeout = $parameters.mysqlClaimTimeout
            mysqlMaxInFlightPerNode = $parameters.mysqlMaxInFlightPerNode
            latencyClassAccounting = $classAccounting
            # 1. Durable duplicate claim: read from the outbox rows of the measurement contest. The
            # warm-up contest is a different contest and cannot contribute to any of these counts.
            durableDuplicateClaim = [ordered]@{
                count = $duplicateClaims
                basis = "SUM(GREATEST(attempts - 1, 0)) over the measurement contest's outbox rows; attempts is written on every claim and never read by the production path, so it is a durable record of how many times a row was handed out beyond the first"
                rowsWithAttemptsAboveOne = $rowsAboveOne
                submissionsWithAttemptsAboveOne = $rowsAboveOne
                attemptsHistogram = $attemptHistogram
                attemptDistributionFile = "claim-attempts.tsv"
                reclaimRowsFile = "stale-reclaims.csv"
                ratePerAccepted = if ($null -eq $duplicateClaims) { $null } else { Get-RatioOrNull $duplicateClaims $acceptedCount }
                # Guarded because $null * 10000 is 0 in PowerShell: multiplying first would turn a
                # missing count into a published "0 per 10k", which reads as "none happened" next to a
                # rate that says "unavailable". Get-RatioOrNull's own null guard cannot fire after the
                # multiplication has already produced a number.
                per10kAccepted = if ($null -eq $duplicateClaims) { $null } else { Get-RatioOrNull ($duplicateClaims * 10000) $acceptedCount 3 }
                staleReclaimsObservedAtClaimTime = $claimStaleRows
                staleReclaimRowsInMeasurementWindow = if ($null -ne $primaryStage) { $primaryStage.claim.staleReclaimRowsInWindow } else { $null }
                staleReclaimWindowBasis = if ($null -ne $primaryStage) { $primaryStage.claim.windowBasis } else { $null }
            }
            # 2. Actual duplicate judge execution: how many submissions were judged more than once.
            # This is the invocation excess, NOT the stale counter. stale is reported beside it so the
            # difference is visible, and the difference has a name: it is the republish count plus the
            # failures, i.e. the reclaims that were answered from the stored result without judging.
            actualDuplicateJudgement = [ordered]@{
                duplicateJudgeExecutions = $duplicateJudgementsFromStale
                duplicateJudgeExecutionsBasis = "judgeSubmission ran more times than there are result rows: stale completions minus the reclaims answered from the stored result and minus the failed executions. A reclaimed row's ORIGINAL execution is fenced as well, so stale on its own counts one non-duplicate execution per reclaimed row and overstates the duplicates by exactly that count"
                duplicateJudgeExecutionsFromInvocations = $duplicateJudgementsFromInvocations
                duplicateJudgeExecutionsFromInvocationsBasis = "the same quantity derived independently as judgeInvocations - uniqueResults - failedExecutions; the two routes agree whenever every submission produced exactly one result row"
                duplicateJudgeExecutionsRoutesAgree = if ($null -eq $duplicateJudgementsFromStale -or $null -eq $duplicateJudgementsFromInvocations) { $null } else { $duplicateJudgementsFromStale -eq $duplicateJudgementsFromInvocations }
                staleTokenCompletions = $staleExecutions
                staleTokenCompletionsBasis = "completion{outcome=stale}: the fenced completion UPDATE matched no row. Each reclaimed row contributes one of these for its original execution, which is not a duplicate judgement, so this value is larger than the duplicate count by republishes + failures"
                uniqueResults = $resultRows
                judgeInvocations = $invocations
                judgeInvocationsMinusResults = $invocationMinusResults
                judgeInvocationsMinusResultsBasis = "judgeInvocations - uniqueResults: the duplicate judgeings plus the failed executions, since a successful submission accounts for exactly one invocation and one result row. Not an upper bound and not an overcount - it is the duplicate count plus failures, which is why failures are subtracted in the derivation above"
                failedExecutions = $failedExecutions
                storedResultRepublishes = $republishes
                accountedCompletions = $accountedCompletions
                accountingResidual = $accountingResidual
                accountingIdentity = "invocations + republishes = results + stale + failure, because every claim ends in exactly one completion and a claim either invokes the judge or is answered from the stored result; success = results because exactly one success publishes each result row. A nonzero residual means a counter path did not record, and is reported rather than folded into the duplicate count"
                ratePerAccepted = if ($null -eq $duplicateJudgementsFromStale) { $null } else { Get-RatioOrNull $duplicateJudgementsFromStale $acceptedCount }
                per10kAccepted = if ($null -eq $duplicateJudgementsFromStale) { $null } else { Get-RatioOrNull ($duplicateJudgementsFromStale * 10000) $acceptedCount 3 }
                duplicateJudgeMillisLowerBound = $duplicateJudgeMillisLowerBound
                duplicateJudgeMillisUpperBound = $duplicateJudgeMillisUpperBound
                duplicateJudgeMillisLowerBoundBasis = "the duplicate judgement count above priced at the deterministic profile's 50ms floor; the lease cannot say how long a discarded attempt actually ran"
                duplicateJudgeMillisUpperBoundBasis = "the same count priced at the profile's 2000ms ceiling; the true cost lies between the two bounds and the profile, not the elapsed time, decides where. The harness records its own bounds from the invocation excess rather than from this count, so the two differ by republishes + failures"
            }
            # 3. Token fencing: what stopped a duplicate execution from writing a second result.
            tokenFencing = [ordered]@{
                staleTokenCompletions = $staleExecutions
                claimStaleObservations = $claimStaleRows
                storedResultRepublishes = $republishes
                storedResultRepublishesBasis = "a reclaimed row whose result already existed is answered from the stored result: the short-circuit returns before the timed judge call, so it costs a republish and not a second execution"
                completionSuccess = $workCost.completionSuccess
                completionSuccessIdentity = "success = results, because exactly one success publishes each result row and a republish publishes an already-published result rather than a new one. Measured in every run; the earlier claim that success = results + republish was false and would have implied that the republishes went unrecorded"
            }
            # The warm-up phase, recorded so the exclusion is checkable rather than asserted.
            warmupExclusion = [ordered]@{
                separateContest = $true
                warmupContestId = if ($null -ne $warmupEvidence) { $warmupEvidence.contestId } else { $null }
                quiescedBeforeBaseline = $warmupQuiesced
                quiescenceSeconds = if ($null -ne $warmupEvidence) { $warmupEvidence.quiescenceSeconds } else { $null }
                acceptedGrowthAfterBaseline = if ($null -ne $warmupEvidence) { $warmupEvidence.acceptedGrowthAfterBaseline } else { $null }
                duplicateClaimsInWarmup = if ($null -ne $warmupEvidence) { $warmupEvidence.duplicateClaimsInWarmup } else { $null }
                excludedFrom = @("accepted", "results", "scoreboard", "latency", "throughput", "duplicateClaims", "judgeInvocationDelta")
                gatlingLog = "warmup-gatling-simulation.log"
                warmupOfferedSubmissions = if (Test-Path (Join-Path $runPath "warmup-gatling-simulation.log")) {
                    @(Get-SubmitHttpRows (Join-Path $runPath "warmup-gatling-simulation.log")).Count
                } else { $null }
            }
            steadyStateQualified = ($failedCriteria.Count -eq 0)
            steadyStateCriteria = $criteria
            failedCriteria = [object[]]$failedCriteria
        }
    }

    # --- fault recovery (SIGKILL) -----------------------------------------------------------------
    # Derived here rather than from the harness's own numbers: the phase function computes the same
    # values so it can decide when to stop, and recomputing them from the preserved samples is what
    # makes them reproducible after the fact. Both are kept, so a disagreement shows.
    if ($stagesDoc.mode -eq "fault-recovery") {
        $faultRecovery = Get-FaultRecoveryAnalysis -LatencyIndexed $latencyIndexed
        if ($null -ne $faultRecovery) {
            foreach ($name in @($faultRecovery.cohorts.Keys)) {
                if ($cohorts.Contains($name)) { continue }
                $cohortNames += $name
                $cohorts[$name] = $faultRecovery.cohorts[$name]
            }
        }
    }

    $staircase = [ordered]@{
        mode = $stagesDoc.mode
        mysqlMaxInFlightPerNode = $parameters.mysqlMaxInFlightPerNode
        mysqlClaimBatchSize = $parameters.mysqlClaimBatchSize
        mysqlClaimTimeout = $parameters.mysqlClaimTimeout
        workerCountPerNode = $parameters.workerCountPerNode
        stageRps = $stagesDoc.stageRps
        warmupStageCount = $stagesDoc.warmupStageCount
        transitionRampSeconds = $stagesDoc.transitionRampSeconds
        stageHoldSeconds = $stagesDoc.stageHoldSeconds
        steadyGuardSeconds = $stagesDoc.steadyGuardSeconds
        overloadThresholdRowsPerSec = $threshold
        latencyCohortCaveat = if ($stagesDoc.mode -eq "fault-recovery") {
            "the run-level cohorts are read from the measurement contest alone, so the warm-up phase - a different contest - is outside every one of them. A fault was injected inside the measured window, so the all cohort spans the pre-fault steady state, the outage, the recovery and the drain together and its p95/p99 are queueing rather than service time. The fault-recovery cohorts partition that same contest: pre-fault-steady is the 25s of steady state before injection, fault-down-arrivals are submissions that arrived between the kill and the node confirming an active dispatcher, reclaimed-after-fault are the submissions whose outbox row was handed out again after the lease expired, post-restart-recovery runs from that confirmation to backlog normalisation, and post-recovery-steady is the load after normalisation. measurement-steady is unavailable by design here. killed-node-claimed stays unavailable because the outbox stores no claim owner."
        } elseif ($stagesDoc.mode -eq "normal-timeout") {
            "the run-level cohorts are read from the measurement contest alone, so the warm-up phase - a different contest - is outside every one of them. What they do span is the measured phase's ramp, its hold and its guard, so their p95/p99 include the ramp and are not service-time readings. measurement-steady restricts the same three latencies to the measured window of the measured stage and is unavailable when that stage did not hold steady. The fault cohorts (fault-window, post-fault-arrivals, killed-node-claimed) do not apply to a run with no fault injected, and pre-fault-normal is simply the whole measurement contest here."
        } else {
            "the run-level cohorts (all, pre-fault-normal, fault-window, post-fault-arrivals) cover the whole run - warm-up, every transition, the overload stages and the drain - so their p95/p99 include queueing and are not service-time readings. measurement-steady restricts the same three latencies to the measurement windows of the stages that held steady with no API refusals, and is unavailable when no stage qualified."
        }
        traceAlignment = $stagesDoc.traceAlignment
        traceAlignmentErrorSeconds = $stagesDoc.traceAlignmentErrorSeconds
        traceAnchorUtc = $stagesDoc.traceAnchorUtc
        tracePlanEndUtc = $stagesDoc.tracePlanEndUtc
        warmupEndedAt = $stagesDoc.warmupEndedAt
        measurementStartedAt = $stagesDoc.measurementStartedAt
        # Drain is a run-level fact: the pipeline is drained once, after the last stage. When the run
        # never reached quiescence the harness leaves these null, which stays null here rather than
        # becoming a zero that would read as an instant drain.
        drainStartedAt = $stagesDoc.drainStartedAt
        drainEndedAt = $stagesDoc.drainEndedAt
        drainSeconds = $stagesDoc.drainSeconds
        expectedPlan = $stagesDoc.expectedPlan
        apiRateLimitSuspected = ($pollutedStages.Count -gt 0)
        apiRateLimitShareThreshold = $ApiRateLimitShare
        samplingInterval = [ordered]@{
            loadTicks = @($timeseries | Where-Object { $_.phase -eq "load" }).Count
            # The warm-up phase of a normal-timeout run samples with its own phase label, so its rows
            # are inside timeseries.csv but outside every window this analysis reads.
            warmupTicks = @($timeseries | Where-Object { $_.phase -eq "warmup" }).Count
            drainTicks = @($timeseries | Where-Object { $_.phase -eq "drain" }).Count
            meanIntervalMs = (Get-ColumnStats @($timeseries | Where-Object { $_.phase -eq "load" }) "sampleIntervalMs").average
            maxIntervalMs = (Get-ColumnStats @($timeseries | Where-Object { $_.phase -eq "load" }) "sampleIntervalMs").max
            meanGatherMs = (Get-ColumnStats @($timeseries | Where-Object { $_.phase -eq "load" }) "sampleElapsedMs").average
        }
        warmupPhase = $stagesDoc.warmupPhase
        capacityKnee = $capacityKnee
        # Stored as a plain array: PowerShell refuses @() around a List[object] read back out of a
        # dictionary, and both the JSON writer and the Markdown writer walk this collection.
        stages = [object[]]$stageResults
        unavailable = [object[]]$unavailableStaircase
    }
}

# The Executor capacity table sits above the measured throughput and is read next to it, so for a
# normal-timeout run it is taken over the measured phase only. The warm-up is a separate contest at
# the same rate, and folding its ticks into the same average would describe neither phase; the
# whole-run maximum is kept in the scope line, because a cap exceeded at any point is worth seeing
# even though it is the measured window's occupancy that explains the measured rate. Staircase and
# fault runs are untouched: their capacity.csv has no warm-up phase to separate out.
$capacityScope = "every sampled tick of the run"
if ($null -ne $staircase -and @("normal-timeout", "fault-recovery") -contains $staircase.mode -and (Test-Path $capacityPath)) {
    $measuredPhaseRows = @(Import-Csv $capacityPath | Where-Object { $_.phase -eq "load" })
    if ($measuredPhaseRows.Count -gt 0) {
        $wholeRunReserved = @(
            @($capacity["judge-1"].reserved.max, $capacity["judge-2"].reserved.max) |
                Where-Object { $null -ne $_ } | Measure-Object -Maximum
        )
        $scoped = [ordered]@{}
        foreach ($node in @("judge-1", "judge-2")) {
            $nodeRows = @($measuredPhaseRows | Where-Object node -eq $node)
            $scoped[$node] = [ordered]@{}
            foreach ($metric in @("running", "localWaiting", "reserved")) {
                $values = @($nodeRows | ForEach-Object { if ([string]$_.$metric -ne "") { [double]$_.$metric } })
                $scoped[$node][$metric] = [ordered]@{
                    samples = $values.Count
                    max = if ($values.Count) { ($values | Measure-Object -Maximum).Maximum } else { $null }
                    average = if ($values.Count) { [math]::Round(($values | Measure-Object -Average).Average, 3) } else { $null }
                }
            }
        }
        $capacity = $scoped
        $capacityScope = "the measured phase only (the ticks the sampler labelled phase=load); the whole run's reserved maximum, warm-up included, was $(if ($wholeRunReserved.Count -and $null -ne $wholeRunReserved[0].Maximum) { $wholeRunReserved[0].Maximum } else { 'unavailable' })"
    }
}

$summary = [ordered]@{
    runId = $parameters.runId
    gitCommit = $parameters.gitCommit
    dispatchMode = $parameters.dispatchMode
    events = $events
    counts = $verification.counts
    integrity = $verification.integrity
    cohorts = $cohorts
    recovery = [ordered]@{
        firstStaleReclaimSeconds = $firstStaleReclaimSeconds
        # Kept under its original name so the normal-timeout comparison and every earlier reader keep
        # working. The definition changed for a fault run, so the definition is named alongside it.
        backlogNormalizedSeconds = $backlogRecoverySeconds
        backlogNormalizedDefinition = "the instant the unfinished backlog first reached zero after the fault"
        faultInjectedAt = $events.faultInjectedAt
        restartRequestedAt = $events.restartRequestedAt
        nodeReadyAt = $events.nodeReadyAt
        throughputRecoveredAt = $events.throughputRecoveredAt
        backlogNormalizedAt = $events.backlogNormalizedAt
        recoveryTimeout = $events.recoveryTimeout
        faultRecoveryReader = "this block is the run's own during-run reading and is kept for the readers that predate the fault-recovery mode. For a fault run read faultRecovery.normalization and faultRecovery.recoveryTimeout instead: those are recomputed over the whole sample series, including the drain, which the run's own reading cannot see"
    }
    workCost = $verification.workCost
    capacity = $capacity
    capacityScope = $capacityScope
    mysql = $verification.mysql
    unavailable = @($verification.unavailable)
}
if ($null -ne $staircase) {
    $summary.staircase = $staircase
    $summary.unavailable = @($verification.unavailable) + @($staircase.unavailable)
}
if ($null -ne $duplication) {
    $summary.duplication = $duplication
}
if ($null -ne $faultRecovery) {
    $summary.faultRecovery = $faultRecovery
    $summary.unavailable = @($verification.unavailable) + @($staircase.unavailable) + @($faultRecovery.unavailable)
}
$summary | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runPath "summary.json") -Encoding utf8

$isNormalTimeout = ($null -ne $staircase -and $staircase.mode -eq "normal-timeout")
$isFaultRecovery = ($null -ne $faultRecovery)
$lines = @(
    "# MySQL judge tradeoff run $($parameters.runId)", "",
    "- Dispatch: $($parameters.dispatchMode)",
    "- Git commit: $($parameters.gitCommit)",
    "- Completed HTTP / accepted / unique / results / scoreboard: $($verification.counts.completedHttpRequests) / $($verification.counts.accepted) / $($verification.counts.uniqueSubmissions) / $($verification.counts.results) / $($verification.counts.scoreboardApplied)"
)
if ($isNormalTimeout) {
    # A fault-free run has no fault window, so the two recovery lines would report "unavailable"
    # measurements of something that was never attempted; the counts above are the measurement
    # contest's, and the warm-up wrote to a different one.
    $lines += @("- Fault injection: none, by design; the counts above are the measurement contest's whole run, and the warm-up wrote to a separate contest.")
} elseif ($isFaultRecovery) {
    $lines += @(
        "- Fault: SIGKILL on $($parameters.killedNode), injected once the trigger window was open and the node was observed to hold work; down for $($faultRecovery.recoveryTimes.downDurationSeconds)s measured as restartRequestedAt - faultInjectedAt against a configured $($faultRecovery.recoveryTimes.downDurationConfiguredSeconds)s",
        "- First stale reclaim observed after fault: $(if ($null -eq $faultRecovery.recoveryTimes.T_staleSeconds) { 'unavailable' } else { [string]$faultRecovery.recoveryTimes.T_staleSeconds + 's' })",
        "- Node ready after the restart request: $(if ($null -eq $faultRecovery.recoveryTimes.T_nodeReadySeconds) { 'unavailable' } else { [string]$faultRecovery.recoveryTimes.T_nodeReadySeconds + 's' })",
        "- Backlog normalization after fault: $(if ($null -eq $faultRecovery.recoveryTimes.T_backlogNormalizationSeconds) { 'unavailable' } else { [string]$faultRecovery.recoveryTimes.T_backlogNormalizationSeconds + 's' })",
        "- Run valid for recovery comparison: $($faultRecovery.runValidForRecovery)"
    )
} else {
    $lines += @(
        "- First stale reclaim after fault: $(if ($null -eq $firstStaleReclaimSeconds) { 'unavailable' } else { [string]$firstStaleReclaimSeconds + 's' })",
        "- Backlog normalization after fault: $(if ($null -eq $backlogRecoverySeconds) { 'unavailable' } else { [string]$backlogRecoverySeconds + 's' })"
    )
}
$lines += @(
    "",
    "| Cohort | Metric | count | p50 ms | p95 ms | p99 ms | max ms |",
    "|---|---|---:|---:|---:|---:|---:|"
)
foreach ($cohortName in $cohortNames) {
    if ($cohorts[$cohortName].available -eq $false) {
        $lines += "| $cohortName | unavailable | 0 |  |  |  |  |"
        continue
    }
    foreach ($metricName in @("L_result_ms", "L_scoreboard_ms", "L_total_ms")) {
        $metric = $cohorts[$cohortName][$metricName]
        $lines += "| $cohortName | $metricName | $($metric.count) | $($metric.p50) | $($metric.p95) | $($metric.p99) | $($metric.max) |"
    }
}
if ($isFaultRecovery) {
    $lines += @("", "## Fault recovery", "")
    $trigger = $faultRecovery.trigger
    $lines += @("### Trigger and the work in flight at the kill", "")
    if ($null -ne $trigger) {
        $lines += @(
            "- Trigger window opened at $($faultRecovery.anchors.faultScheduledAt.value), target $($parameters.killedNode)",
            "- Primary condition: $($trigger.primaryMinRunning) running and $($trigger.primaryMinReserved) reserved; waited $($trigger.waitedSeconds)s",
            "- Escalation: $($trigger.escalation); active work seen: $($trigger.activeWorkSeen); fault injected with active work: $(-not [bool]$trigger.faultNotInjectedWithActiveWork)",
            "- judge-1 running/reserved/queued at the decision: $($trigger.judge1.running) / $($trigger.judge1.reserved) / $($trigger.judge1.queued); judge-2: $($trigger.judge2.running) / $($trigger.judge2.reserved) / $($trigger.judge2.queued)"
        )
    } else {
        $lines += @("- No trigger record was preserved for this run")
    }
    $kill = $faultRecovery.claimedUnfinishedAtKill
    $lines += @(
        "- Kill-time unfinished outbox (contest): $($verification.faultRecovery.killSnapshot.unfinishedOutboxContest); scoreboard pending: $($verification.faultRecovery.killSnapshot.scoreboardPending)",
        "- Cluster-wide claimed unfinished upper bound: $($kill.clusterWideUpperBound) rows (attribution exact: $($kill.attributionExact)); claimed_at age at kill p50/p95/max: $($kill.ageSecondsAtKill.p50) / $($kill.ageSecondsAtKill.p95) / $($kill.ageSecondsAtKill.max) s",
        "",
        "### Recovery anchors",
        "",
        "| Anchor | Value | After fault s | After restart request s | Source |",
        "|---|---|---:|---:|---|"
    )
    foreach ($anchorName in @($faultRecovery.anchors.Keys)) {
        $anchor = $faultRecovery.anchors[$anchorName]
        $lines += "| $anchorName | $($anchor.value) | $($anchor.secondsAfterFault) | $($anchor.secondsAfterRestartRequested) | $($anchor.source) |"
    }
    # throughputRecoveredAt and backlogNormalizedAt are recomputed here from the samples rather than
    # read from events.json, and the harness derived its own copies while the run was live. Both are
    # kept, so this states whether the run's own live derivation and the post-hoc one landed on the
    # same instant; a NO means the two boundaries below are the analyzer's, not the harness's.
    $lines += @(
        "",
        "Harness and analyzer agree on the derived instants: throughput recovery $($faultRecovery.throughput.harnessAndAnalyzerAgree) (harness $($faultRecovery.throughput.harnessReportedAt), analyzer $($faultRecovery.throughput.recoveredAt)), backlog normalization $($faultRecovery.normalization.harnessAndAnalyzerAgree) (harness $($faultRecovery.normalization.harnessReportedAt), analyzer $($faultRecovery.normalization.backlogNormalizedAt)). A False is not automatically an error: the run decided its own value during the run from the fault phase's series, which stops before the drain, so a normalisation seen in the drain is reported as absent by the run and found by the recomputation"
    )
    if ($null -ne $faultRecovery.postRecoveryWindow) {
        $lines += @(
            "",
            "Post-recovery window available: $($faultRecovery.postRecoveryWindow.available) ($($faultRecovery.postRecoveryWindow.reason)); actual $($faultRecovery.postRecoveryWindow.actualSeconds)s of load, $($faultRecovery.postRecoveryWindow.sampleCount) samples"
        )
    }
    $lines += @(
        "",
        "### Pre-fault baselines",
        "",
        "| Quantity | Value | Samples |",
        "|---|---:|---:|",
        "| judge backlog p95 | $($faultRecovery.preFault.judgeBacklogP95) | $($faultRecovery.preFault.judgeBacklogSamples) |",
        "| scoreboard pending p95 | $($faultRecovery.preFault.scoreboardPendingP95) | $($faultRecovery.preFault.scoreboardPendingSamples) |",
        "| result RPS | $($faultRecovery.preFault.resultRps) | |",
        "",
        "Backlog normalization: judge backlog at $($faultRecovery.normalization.judgeSecondsAfterFault)s, scoreboard pending at $($faultRecovery.normalization.scoreboardSecondsAfterFault)s, combined at $($faultRecovery.recoveryTimes.T_backlogNormalizationSeconds)s after the fault.",
        "Throughput recovery: $($faultRecovery.recoveryTimes.T_throughputRecoverySeconds)s after the fault, threshold $($faultRecovery.throughput.thresholdRps) RPS over $($faultRecovery.throughput.windowSeconds)s rolling windows.",
        "Reclaimed rows: $($faultRecovery.reclaimAccounting.reclaimedRowsAfterFault) ($($faultRecovery.reclaimAccounting.label)); last reclaimed result at $($faultRecovery.recoveryTimes.T_lastReclaimedResultSeconds)s and its scoreboard at $($faultRecovery.recoveryTimes.T_lastReclaimedScoreboardSeconds)s after the fault.",
        "",
        "### Cohort detail",
        "",
        "| Cohort | n | fast / slow | L_total p50 | p95 | p99 | max | over-5s share | over-10s share | available |",
        "|---|---:|---|---:|---:|---:|---:|---:|---:|---|"
    )
    foreach ($cohortName in @($faultRecovery.cohorts.Keys)) {
        $cohort = $faultRecovery.cohorts[$cohortName]
        if ($cohort.available -eq $false) {
            $lines += "| $cohortName | 0 | | | | | | | | no: $($cohort.reason) |"
            continue
        }
        $total = $cohort.L_total_ms
        $lines += "| $cohortName | $($cohort.submissionCount) | $($cohort.byLatencyClass.fast.submissionCount) / $($cohort.byLatencyClass.slow.submissionCount) | $($total.p50) | $($total.p95) | $($total.p99) | $($total.max) | $($cohort.over5sRatio) | $($cohort.over10sRatio) | yes |"
    }
    foreach ($latencyClass in @("fast", "slow")) {
        $lines += @("", "#### $latencyClass split", "", "| Cohort | n | L_result p50 | p95 | p99 | L_total p50 | p95 | p99 | max |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        foreach ($cohortName in @($faultRecovery.cohorts.Keys)) {
            $cohort = $faultRecovery.cohorts[$cohortName]
            if ($cohort.available -eq $false) { continue }
            $class = $cohort.byLatencyClass[$latencyClass]
            $lines += "| $cohortName | $($class.submissionCount) | $($class.L_result_ms.p50) | $($class.L_result_ms.p95) | $($class.L_result_ms.p99) | $($class.L_total_ms.p50) | $($class.L_total_ms.p95) | $($class.L_total_ms.p99) | $($class.L_total_ms.max) |"
        }
    }
    $lines += @("", "### Limits recorded for this fault run", "")
    foreach ($note in @($faultRecovery.unavailable)) { $lines += "- $note" }
}
$lines += @("", "## Executor capacity", "", "| Node | Metric | samples | max | average |", "|---|---|---:|---:|---:|")
foreach ($node in @("judge-1", "judge-2")) {
    foreach ($metricName in @("running", "localWaiting", "reserved")) {
        $metric = if ($capacity[$node]) {
            $capacity[$node][$metricName]
        } else {
            [ordered]@{ samples=0; max=$null; average=$null }
        }
        $lines += "| $node | $metricName | $($metric.samples) | $($metric.max) | $($metric.average) |"
    }
}
if ($isNormalTimeout) { $lines += @("", "Executor scope: $capacityScope.") }
if ($null -ne $staircase) {
    # Without this, the run-level cohort tail and the per-stage tail look like the same kind of
    # number, and the run-level one is a mixture.
    $lines += @("", "Cohort scope: $($staircase.latencyCohortCaveat)")
}
$lines += @("", "## Explicitly unavailable", "")
if ($summary.unavailable.Count -eq 0) { $lines += "- None" } else {
    $lines += @($summary.unavailable | ForEach-Object { "- $_" })
}

if ($null -ne $staircase) {
    $knee = $staircase.capacityKnee
    if ($staircase.mode -eq "normal-timeout") {
        $lines += @(
            "", "## Single-rate normal-timeout run", "",
            "- max-in-flight per node: $($staircase.mysqlMaxInFlightPerNode), claim batch $($staircase.mysqlClaimBatchSize), claim timeout $($staircase.mysqlClaimTimeout), workers/node $($staircase.workerCountPerNode)",
            "- Offered rate: $($staircase.stageRps -join ', ') RPS, ramp $($staircase.transitionRampSeconds)s, hold $($staircase.stageHoldSeconds)s, guard $($staircase.steadyGuardSeconds)s",
            "- Phase windows vs the injected plan: $($staircase.traceAlignment) (last request $(if ($null -eq $staircase.traceAlignmentErrorSeconds) { 'unavailable' } else { [string]$staircase.traceAlignmentErrorSeconds + 's from the predicted end' }))",
            "- Warm-up: a separate contest at the same rate, drained to quiescence before the baseline scrape; its rows are outside every count below. Ticks sampled while it ran: $($staircase.samplingInterval.warmupTicks).",
            "- Sampler period: mean $($staircase.samplingInterval.meanIntervalMs) ms, max $($staircase.samplingInterval.maxIntervalMs) ms, mean gather cost $($staircase.samplingInterval.meanGatherMs) ms",
            "- Capacity knee: not defined for this run - a single offered rate cannot bracket a knee, and the interval text below is a one-rung ladder, not a capacity reading.",
            "- API rate limit suspected: $(if ($staircase.apiRateLimitSuspected) { 'yes' } else { 'no' })"
        )
        if ($null -ne $duplication) {
            $dc = $duplication.durableDuplicateClaim
            $dj = $duplication.actualDuplicateJudgement
            $tf = $duplication.tokenFencing
            $lines += @(
                "", "### Duplicate claims and duplicate judging", "",
                "Three different quantities. They are not interchangeable: a duplicate claim only becomes a duplicate judgement if the earlier attempt is still running when the row is reclaimed, and the fence is what stops the second one from writing a second result.",
                "",
                "| Quantity | Value | per accepted | per 10k accepted |",
                "|---|---:|---:|---:|",
                "| Durable duplicate claim (attempts > 1) | $(if ($null -eq $dc.count) { 'unavailable' } else { $dc.count }) | $(if ($null -eq $dc.ratePerAccepted) { 'unavailable' } else { $dc.ratePerAccepted }) | $(if ($null -eq $dc.per10kAccepted) { 'unavailable' } else { $dc.per10kAccepted }) |",
                "| Actual duplicate judge execution (invocation excess) | $(if ($null -eq $dj.duplicateJudgeExecutions) { 'unavailable' } else { $dj.duplicateJudgeExecutions }) | $(if ($null -eq $dj.ratePerAccepted) { 'unavailable' } else { $dj.ratePerAccepted }) | $(if ($null -eq $dj.per10kAccepted) { 'unavailable' } else { $dj.per10kAccepted }) |",
                "| Stale token completions (not the duplicate count) | $(if ($null -eq $tf.staleTokenCompletions) { 'unavailable' } else { $tf.staleTokenCompletions }) | | |",
                "",
                "- Accepted submissions: $(if ($null -eq $duplication.acceptedSubmissions) { 'unavailable' } else { $duplication.acceptedSubmissions }) (unique $(if ($null -eq $duplication.uniqueSubmissions) { 'unavailable' } else { $duplication.uniqueSubmissions })); unique results $(if ($null -eq $dj.uniqueResults) { 'unavailable' } else { $dj.uniqueResults }); judge invocations $(if ($null -eq $dj.judgeInvocations) { 'unavailable' } else { $dj.judgeInvocations }).",
                "- Duplicate judge executions, two independent routes: stale - republishes - failures = $(if ($null -eq $dj.duplicateJudgeExecutions) { 'unavailable' } else { $dj.duplicateJudgeExecutions }); invocations - results - failures = $(if ($null -eq $dj.duplicateJudgeExecutionsFromInvocations) { 'unavailable' } else { $dj.duplicateJudgeExecutionsFromInvocations }); agree = $(if ($null -eq $dj.duplicateJudgeExecutionsRoutesAgree) { 'unavailable' } elseif ($dj.duplicateJudgeExecutionsRoutesAgree) { 'yes' } else { 'NO' }). Stale alone ($(if ($null -eq $tf.staleTokenCompletions) { 'unavailable' } else { $tf.staleTokenCompletions })) is larger because a reclaimed row's original execution is fenced too.",
                "- Accounting: invocations $(if ($null -eq $dj.judgeInvocations) { 'unavailable' } else { $dj.judgeInvocations }) + republishes $(if ($null -eq $tf.storedResultRepublishes) { 'unavailable' } else { $tf.storedResultRepublishes }) = results $(if ($null -eq $dj.uniqueResults) { 'unavailable' } else { $dj.uniqueResults }) + stale $(if ($null -eq $tf.staleTokenCompletions) { 'unavailable' } else { $tf.staleTokenCompletions }) + failures $(if ($null -eq $dj.failedExecutions) { 'unavailable' } else { $dj.failedExecutions }); residual $(if ($null -eq $dj.accountingResidual) { 'unavailable' } else { $dj.accountingResidual }).",
                "- Discarded judge work, priced at the deterministic profile from the duplicate count: $(if ($null -eq $dj.duplicateJudgeMillisLowerBound) { 'unavailable' } else { "$($dj.duplicateJudgeMillisLowerBound)-$($dj.duplicateJudgeMillisUpperBound) ms" }).",
                # Read the keys, not PSObject.Properties: on an OrderedDictionary the latter yields the
                # dictionary's own members (Count, Keys, Values...), which would print a property dump
                # in place of the histogram.
                "- Attempts histogram (attempts: rows): $(if (@($dc.attemptsHistogram.Keys).Count -eq 0) { 'unavailable' } else { (@($dc.attemptsHistogram.Keys) | ForEach-Object { "$($_):$($dc.attemptsHistogram[$_])" }) -join ', ' })",
                "- Steady-state qualified: $(if ($duplication.steadyStateQualified) { 'yes' } else { 'no - failed: ' + ($duplication.failedCriteria -join ', ') })",
                "",
                "| Inclusion criterion | Result |",
                "|---|---|"
            )
            foreach ($criterionName in @($duplication.steadyStateCriteria.Keys)) {
                $lines += "| $criterionName | $($duplication.steadyStateCriteria[$criterionName]) |"
            }
        }
    } else {
    $lines += @(
        "", "## max-in-flight capacity staircase", "",
        "- max-in-flight per node: $($staircase.mysqlMaxInFlightPerNode), claim batch $($staircase.mysqlClaimBatchSize), claim timeout $($staircase.mysqlClaimTimeout), workers/node $($staircase.workerCountPerNode)",
        "- Stage ladder: $($staircase.stageRps -join ', ') RPS, warm-up stages $($staircase.warmupStageCount), hold $($staircase.stageHoldSeconds)s, guard $($staircase.steadyGuardSeconds)s",
        "- Stage windows vs the injected plan: $($staircase.traceAlignment) (last request $(if ($null -eq $staircase.traceAlignmentErrorSeconds) { 'unavailable' } else { [string]$staircase.traceAlignmentErrorSeconds + 's from the predicted end' }))",
        "- Sampler period: mean $($staircase.samplingInterval.meanIntervalMs) ms, max $($staircase.samplingInterval.maxIntervalMs) ms, mean gather cost $($staircase.samplingInterval.meanGatherMs) ms",
        "- Capacity knee: $(if ($null -eq $knee.sustainedSteadyUpToRps -and $null -eq $knee.firstOverloadStageRps) { 'unknown' } else { $knee.intervalDescription })",
        "- Knee basis: $($knee.classifiedStageCount) of $($knee.measuredStageCount) measured stages could be classified, $($knee.unclassifiableStageCount) could not, $($knee.refusedStageCount) were refused; the knee's lower stage $(if ($knee.sustainedSteadyStageVerdictRobust) { 'survives a small change to the rule' } else { 'does not survive a small change to the rule (margin ' + $(if ($null -eq $knee.sustainedSteadyStageMarginRowsPerSec) { 'unavailable' } else { [string]$knee.sustainedSteadyStageMarginRowsPerSec + ' rows/s' }) + ' below the threshold)' })",
        "- API rate limit suspected: $(if ($staircase.apiRateLimitSuspected) { 'yes' } else { 'no' })",
        "",
        "| Stage | target RPS | window s | measured s | class | total backlog rows/s | first half | second half | achieved OK RPS | offered RPS | success % | 429 | 503 | p95 L_total ms | p99 L_total ms | running max (both) | queued max (both) | reserved max (both) | steady-state latency usable |",
        "|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"
    )
    foreach ($stage in $staircase.stages) {
        $rowsPerSec = $stage.backlogGrowth.totalRowsPerSec
        $lines += "| $($stage.label) | $($stage.targetRps) | $($stage.windowSeconds) | $($stage.measurementSeconds) | $($stage.classification) | " +
            "$(if ($null -eq $rowsPerSec) { 'unavailable' } else { $rowsPerSec }) | " +
            "$($stage.backlogByHalf.firstHalfRowsPerSec) | $($stage.backlogByHalf.secondHalfRowsPerSec) | " +
            "$(if ($null -eq $stage.http.achievedOkRps) { 'unavailable' } else { $stage.http.achievedOkRps }) | " +
            "$(if ($null -eq $stage.http.offeredRps) { 'unavailable' } else { $stage.http.offeredRps }) | " +
            "$(if ($null -eq $stage.http.successPercent) { 'unavailable' } else { $stage.http.successPercent }) | " +
            "$(if ($null -eq $stage.http.ko429) { 'unavailable' } else { $stage.http.ko429 }) | " +
            "$(if ($null -eq $stage.http.ko503) { 'unavailable' } else { $stage.http.ko503 }) | " +
            "$($stage.latency.L_total_ms.p95) | $($stage.latency.L_total_ms.p99) | " +
            "$($stage.executor.bothNodes.running.max) | $($stage.executor.bothNodes.queued.max) | $($stage.executor.bothNodes.reserved.max) | " +
            "$(if ($stage.reliableAsSteadyStateLatency) { 'yes' } else { 'no' }) |"
    }
    $lines += @("", "### Where the percentiles may be read as service latency", "")
    foreach ($stage in $staircase.stages) {
        if ($stage.isWarmup) { continue }
        $lines += "- $($stage.label) ($($stage.targetRps) RPS, $($stage.classification)): $(if ($stage.reliableAsSteadyStateLatency) { 'reliable' } else { 'not reliable' }) - $($stage.reliableAsSteadyStateLatencyReason)"
    }
    if (@($knee.stagesExcludedFromKnee).Count -gt 0) {
        $lines += @("", "### Stages that could not be classified", "")
        foreach ($excluded in $knee.stagesExcludedFromKnee) {
            $lines += "- $($excluded.targetRps) RPS: $($excluded.reason)"
        }
    }
    if (@($knee.stagesPollutedByApiRateLimit).Count -gt 0) {
        $lines += @("", "### Stages the API rate limiter confounded", "")
        foreach ($polluted in $knee.stagesPollutedByApiRateLimit) {
            $lines += "- $($polluted.targetRps) RPS: $($polluted.reason)"
        }
    }
    $lines += @("", "### Per-stage duplicate and stale work", "",
        "| Stage | stale reclaim rows (updated_at proxy) | claim_stale delta | stale completions | duplicate judgeings | judge invocations | claim calls | claim rows | rejected | duplicate judge ms (lower-upper) |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    foreach ($stage in $staircase.stages) {
        $claim = $stage.claim
        $lines += "| $($stage.label) | $($claim.staleReclaimRowsInWindow) | $(if ($null -eq $claim.claimStaleDelta) { 'unavailable' } else { $claim.claimStaleDelta }) | " +
            "$(if ($null -eq $claim.staleCompletionDelta) { 'unavailable' } else { $claim.staleCompletionDelta }) | " +
            "$(if ($null -eq $claim.duplicateJudgementCount) { 'unavailable' } else { $claim.duplicateJudgementCount }) | " +
            "$(if ($null -eq $claim.judgeInvocationDelta) { 'unavailable' } else { $claim.judgeInvocationDelta }) | " +
            "$(if ($null -eq $claim.claimCallsDelta) { 'unavailable' } else { $claim.claimCallsDelta }) | " +
            "$(if ($null -eq $claim.claimRowsDelta) { 'unavailable' } else { $claim.claimRowsDelta }) | " +
            "$(if ($null -eq $claim.executorRejectionsDelta) { 'unavailable' } else { $claim.executorRejectionsDelta }) | " +
            "$(if ($null -eq $claim.duplicateJudgementMillisLowerBound) { 'unavailable' } else { "$($claim.duplicateJudgementMillisLowerBound)-$($claim.duplicateJudgementMillisUpperBound)" }) |"
    }
    $lines += @("", "Per-stage drain is unavailable by construction: $($staircase.stages[0].drain.reason)")
    $lines += @("", "### Why the achieved rate falls short of workers divided by service time", "",
        "| Stage | offered vs target % | timed judge mean ms | timed samples | implied occupancy ms | untimed per claim ms | running mean (both) | of $(2 * [int]$staircase.workerCountPerNode) workers | claims/s | rows per claim (batch $($staircase.mysqlClaimBatchSize)) |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($stage in $staircase.stages) {
        $mech = $stage.mechanism
        $lines += "| $($stage.label) | $(if ($null -eq $stage.offeredVsTargetPercent) { 'unavailable' } else { $stage.offeredVsTargetPercent }) | " +
            "$(if ($null -eq $mech.timedJudgeMeanMs) { 'unavailable' } else { $mech.timedJudgeMeanMs }) | " +
            "$(if ($null -eq $mech.timedJudgeSampleCount) { 'unavailable' } else { $mech.timedJudgeSampleCount }) | " +
            "$(if ($null -eq $mech.impliedOccupancyMs) { 'unavailable' } else { $mech.impliedOccupancyMs }) | " +
            "$(if ($null -eq $mech.untimedPerClaimMs) { 'unavailable' } else { $mech.untimedPerClaimMs }) | " +
            "$(if ($null -eq $mech.runningMeanBothNodes) { 'unavailable' } else { $mech.runningMeanBothNodes }) | " +
            "$(if ($null -eq $mech.workerUtilization) { 'unavailable' } else { $mech.workerUtilization }) | " +
            "$(if ($null -eq $mech.claimsPerSecond) { 'unavailable' } else { $mech.claimsPerSecond }) | " +
            "$(if ($null -eq $mech.rowsPerClaim) { 'unavailable' } else { $mech.rowsPerClaim }) |"
    }
    $lines += @("", "Implied occupancy: $($staircase.stages[0].mechanism.impliedOccupancyBasis)")
    $lines += @("", "### Would a small change have flipped the steady verdict?", "",
        "| Stage | endpoint rows/s | least squares rows/s | tick stdev rows | largest single tick swing | net rows that would exceed the threshold | verdict at half / double threshold | without first / last sample | stable |",
        "|---|---:|---:|---:|---:|---:|---|---|---|")
    foreach ($stage in $staircase.stages) {
        $rob = $stage.growthRobustness
        $lines += "| $($stage.label) | $(if ($null -eq $stage.backlogGrowth.totalRowsPerSec) { 'unavailable' } else { $stage.backlogGrowth.totalRowsPerSec }) | " +
            "$(if ($null -eq $rob.leastSquaresRowsPerSec) { 'unavailable' } else { $rob.leastSquaresRowsPerSec }) | " +
            "$(if ($null -eq $rob.perTickStdevRows) { 'unavailable' } else { $rob.perTickStdevRows }) | " +
            "$(if ($null -eq $rob.maxSingleTickSwingRows) { 'unavailable' } else { $rob.maxSingleTickSwingRows }) | " +
            "$(if ($null -eq $rob.netRowsThatWouldExceedThreshold) { 'unavailable' } else { $rob.netRowsThatWouldExceedThreshold }) | " +
            "$(if ($null -eq $rob.classificationAtHalfThreshold) { 'unavailable' } else { "$($rob.classificationAtHalfThreshold) / $($rob.classificationAtDoubleThreshold)" }) | " +
            "$(if ($null -eq $rob.classificationWithoutFirstSample) { 'unavailable' } else { "$($rob.classificationWithoutFirstSample) / $($rob.classificationWithoutLastSample)" }) | " +
            "$(if ($null -eq $rob.stable) { 'unavailable' } else { $rob.stable }) |"
    }
    $lines += @("", "### Sampler honesty inside each measurement window", "",
        "| Stage | ticks | mean interval ms | max interval ms | ticks slower than 1500 ms | max boundary snapshot lag ms |",
        "|---|---:|---:|---:|---:|---:|")
    foreach ($stage in $staircase.stages) {
        $tick = $stage.tickHonesty
        $lines += "| $($stage.label) | $($tick.ticks) | $(if ($null -eq $tick.meanIntervalMs) { 'unavailable' } else { $tick.meanIntervalMs }) | " +
            "$(if ($null -eq $tick.maxIntervalMs) { 'unavailable' } else { $tick.maxIntervalMs }) | " +
            "$($tick.ticksSlowerThan1500Ms) | " +
            "$(if ($null -eq $tick.maxBoundaryLagMs) { 'unavailable' } else { $tick.maxBoundaryLagMs }) |"
    }
    $lines += @("", "Theory reference only, not a fitting target: $($knee.theoryReference.note)")
    }
}
$lines | Set-Content (Join-Path $runPath "summary.md") -Encoding utf8

Write-Host "Wrote summary.json and summary.md to $runPath"

# Without an explicit exit the script leaves $LASTEXITCODE unset, so a caller that reports the code
# of a completed analysis gets an empty string instead of 0.
exit 0
