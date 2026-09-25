[CmdletBinding()]
param(
    # Run directories in the order they were executed, each already analyzed by
    # Analyze-TradeoffRun.ps1 so that summary.json carries the staircase section.
    [Parameter(Mandatory = $true)][string[]]$RunDirectory,
    # Defaults to the folder that holds the runs, so the comparison lands beside them.
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"

function Get-Maximum {
    param([object[]]$Values)
    $present = @($Values | Where-Object { $null -ne $_ })
    if ($present.Count -eq 0) { return $null }
    return ($present | Measure-Object -Maximum).Maximum
}

function Get-Average {
    param([object[]]$Values)
    $present = @($Values | Where-Object { $null -ne $_ })
    if ($present.Count -eq 0) { return $null }
    return [math]::Round(($present | Measure-Object -Average).Average, 3)
}

function Get-Sum {
    param([object[]]$Values)
    $present = @($Values | Where-Object { $null -ne $_ })
    if ($present.Count -eq 0) { return $null }
    return [math]::Round(($present | Measure-Object -Sum).Sum, 3)
}

# The median, not the maximum: the saturated plateau of a run moves as its backlog deepens, so the
# single best second would report a peak the pipeline only touched once.
function Get-Median {
    param([object[]]$Values)
    $present = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($present.Count -eq 0) { return $null }
    if ($present.Count % 2 -eq 1) { return [math]::Round($present[[math]::Floor($present.Count / 2)], 3) }
    return [math]::Round((($present[$present.Count / 2 - 1] + $present[$present.Count / 2]) / 2), 3)
}

# "100ms", "20ms", "1s", "PT0.1S" -> milliseconds, for ordering runs by poll interval. $null when the
# text is not one of those forms, which leaves the run unordered rather than misplaced.
function ConvertTo-DurationMillis {
    param([string]$Text)
    if (-not $Text) { return $null }
    $t = $Text.Trim().ToLowerInvariant()
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    if ($t -match '^([0-9.]+)ms$') { return [double]::Parse($Matches[1], $culture) }
    if ($t -match '^([0-9.]+)s$') { return 1000.0 * [double]::Parse($Matches[1], $culture) }
    if ($t -match '^pt([0-9.]+)s$') { return 1000.0 * [double]::Parse($Matches[1], $culture) }
    if ($t -match '^[0-9]+$') { return [double]$t }
    return $null
}

# The 32-worker reference every capacity document quotes: 2 nodes x 16 workers / 147.5ms mean judge time.
$TheoryResultRps = 2 * 16 / 0.1475

function Get-MifRatio {
    param($ByMif, [int]$NumeratorMif, [int]$DenominatorMif, [string]$Field, [string]$BlockedReason = "")
    $result = [ordered]@{
        numeratorMif = $NumeratorMif
        denominatorMif = $DenominatorMif
        field = $Field
        numeratorRunId = $null
        denominatorRunId = $null
        numeratorValue = $null
        denominatorValue = $null
        ratio = $null
        available = $false
        reason = $null
    }
    if ($BlockedReason) { $result.reason = $BlockedReason; return $result }
    if (-not $ByMif.ContainsKey($NumeratorMif)) { $result.reason = "no analyzed run with max-in-flight $NumeratorMif"; return $result }
    if (-not $ByMif.ContainsKey($DenominatorMif)) { $result.reason = "no analyzed run with max-in-flight $DenominatorMif"; return $result }
    $numerator = $ByMif[$NumeratorMif]
    $denominator = $ByMif[$DenominatorMif]
    $result.numeratorRunId = $numerator.runId
    $result.denominatorRunId = $denominator.runId
    $result.numeratorValue = $numerator.throughput[$Field]
    $result.denominatorValue = $denominator.throughput[$Field]
    if ($null -eq $result.numeratorValue -or $null -eq $result.denominatorValue) {
        $result.reason = "one or both runs have no value for $Field"
        return $result
    }
    if ($result.denominatorValue -eq 0) {
        $result.reason = "the $DenominatorMif run achieved 0 for $Field, so a ratio would be undefined"
        return $result
    }
    $result.ratio = [math]::Round($result.numeratorValue / $result.denominatorValue, 3)
    $result.available = $true
    return $result
}

$analyzed = New-Object System.Collections.Generic.List[object]
$excluded = New-Object System.Collections.Generic.List[object]

foreach ($directory in $RunDirectory) {
    $runPath = (Resolve-Path $directory).Path
    $summaryPath = Join-Path $runPath "summary.json"
    if (-not (Test-Path $summaryPath)) {
        # A directory with no events.json never reached the point where the runner records the
        # outcome, which is what an interrupted run looks like: the sampler's files are there and
        # the reason is not. Saying so is more useful than "never analyzed".
        $partial = @(@("timeseries.csv", "capacity.csv", "stage-trace.csv") | Where-Object { Test-Path (Join-Path $runPath $_) })
        $reason = if (-not (Test-Path (Join-Path $runPath "events.json")) -and $partial.Count -gt 0) {
            "interrupted: the run directory holds $($partial -join ', ') but no events.json and no summary.json, so the harness never recorded an outcome and there is no reason to read"
        } else { "summary.json is missing, so the run was never analyzed or never finished" }
        $excluded.Add([ordered]@{ runDirectory = $runPath; reason = $reason })
        continue
    }
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    if ($null -eq $summary.staircase) {
        $excluded.Add([ordered]@{ runDirectory = $runPath; runId = $summary.runId; reason = "the run has no staircase section; it predates the staircase harness or ran without -Staircase" })
        continue
    }
    $staircase = $summary.staircase
    if ($null -eq $staircase.mysqlMaxInFlightPerNode) {
        # max-in-flight is the condition this whole comparison is grouped by. `[int]$null` is 0, which
        # would print a max-in-flight the run never used and could collide with a real run's key.
        $excluded.Add([ordered]@{ runDirectory = $runPath; runId = $summary.runId; reason = "the summary records no max-in-flight per node, so the run cannot be placed on the comparison axis" })
        continue
    }
    $measured = [object[]]@($staircase.stages | Where-Object { -not $_.isWarmup })
    $steady = [object[]]@($measured | Where-Object { $_.classification -eq "steady" })
    $overloaded = [object[]]@($measured | Where-Object { $_.classification -eq "overloaded" })
    # The throughput ceiling a run actually reached. The top plateaus are where a saturated
    # pipeline does its most, so their maximum is the closest thing to a measured capacity; the
    # steady-only maximum is the same question asked of stages that never fell behind.
    $throughput = [ordered]@{
        peakAchievedResultRps = Get-Maximum @($measured | ForEach-Object { $_.resultsCompleted.perSecond })
        peakAchievedOkRps = Get-Maximum @($measured | ForEach-Object { $_.http.achievedOkRps })
        highestSteadyResultRps = Get-Maximum @($steady | ForEach-Object { $_.resultsCompleted.perSecond })
        highestSteadyTargetRps = Get-Maximum @($steady | ForEach-Object { $_.targetRps })
        firstOverloadTargetRps = if ($overloaded.Count -gt 0) { $overloaded[0].targetRps } else { $null }
        interpretedAs = "peakAchievedResultRps is the highest single-second result rate seen in any measured stage, so once load exceeds capacity it is the deepest-queueing stage's best second rather than a sustainable rate; highestSteadyResultRps is bounded by the highest ladder rung that held. Neither is a capacity estimate; saturatedResultRpsMedian is the one to read as the measured plateau"
    }
    # Only stages that had already fallen behind are running the pipeline flat out, so their median
    # result rate is the measured analogue of the theory reference. The per-stage trend is kept
    # beside it because the plateau is not flat: a deeper queue can lower it.
    $saturatedValues = @($overloaded | ForEach-Object { $_.resultsCompleted.perSecond })
    $throughput.saturatedResultRpsMedian = Get-Median $saturatedValues
    $throughput.saturatedStageCount = @($saturatedValues | Where-Object { $null -ne $_ }).Count
    $throughput.saturatedTrend = [object[]]@($overloaded | ForEach-Object {
        [ordered]@{ label = $_.label; targetRps = $_.targetRps; resultRps = $_.resultsCompleted.perSecond }
    })
    $throughput.saturatedCaveat = "an overloaded stage is labelled overloadQueueingResults, so its result rate is a saturated plateau and not a service rate"

    # Four failed verification queries would each come back as 0 and make 0 == 0 == 0 == 0 read as a
    # pass, so a zero or missing count is treated as absent evidence rather than as a verified empty
    # run. completedHttpRequests is deliberately not part of this: it is a client-side count that
    # legitimately trails the server-side one when maxDuration truncates Gatling's log.
    $integrityCountNames = @("accepted", "uniqueSubmissions", "results", "scoreboardApplied")
    $missingCounts = @($integrityCountNames | Where-Object { $null -eq $summary.counts.$_ -or [double]$summary.counts.$_ -le 0 })
    $integrityCountsComplete = ($missingCounts.Count -eq 0)
    $integrityPassed = [bool]$summary.integrity.passed -and $integrityCountsComplete
    $integrityReason = if (-not $integrityCountsComplete) {
        "verification counts are missing or zero for $($missingCounts -join ', '), so the integrity check could not confirm the run"
    } elseif (-not [bool]$summary.integrity.passed) {
        "integrity failed: lostOrIncomplete=$($summary.integrity.lostOrIncomplete), finalResultMismatch=$($summary.integrity.finalResultMismatch)"
    } else { $null }

    # The upper margin is a row count and the lower one is a rate; older summaries carry the upper
    # margin under its old RowsPerSec name. Reading it here keeps the table cell from printing an
    # empty margin.
    $kneeUpperMarginRows = $staircase.capacityKnee.firstOverloadStageMarginRows
    if ($null -eq $kneeUpperMarginRows) {
        $kneeUpperMarginRows = $staircase.capacityKnee.firstOverloadStageMarginRowsPerSec
    }
    # max-in-flight is validated above, so it is present for every analyzed run.
    $maxInFlight = [int]$staircase.mysqlMaxInFlightPerNode
    # Older summaries do not carry the poll interval; the run's own parameters.json always has it.
    $pollInterval = $staircase.mysqlPollInterval
    if ($null -eq $pollInterval) {
        $parametersPath = Join-Path $runPath "parameters.json"
        if (Test-Path $parametersPath) { $pollInterval = (Get-Content $parametersPath -Raw | ConvertFrom-Json).mysqlPollInterval }
    }

    $analyzed.Add([pscustomobject]@{
        runId = $summary.runId
        runDirectory = $runPath
        maxInFlight = $maxInFlight
        pollInterval = $pollInterval
        pollIntervalMillis = ConvertTo-DurationMillis ([string]$pollInterval)
        idleBaseline = $summary.idleBaseline
        claimBatchSize = $staircase.mysqlClaimBatchSize
        claimTimeout = $staircase.mysqlClaimTimeout
        workerCountPerNode = $staircase.workerCountPerNode
        stageRps = $staircase.stageRps
        stageHoldSeconds = $staircase.stageHoldSeconds
        steadyGuardSeconds = $staircase.steadyGuardSeconds
        warmupStageCount = $staircase.warmupStageCount
        integrityPassed = $integrityPassed
        integrityCountsComplete = $integrityCountsComplete
        integrityLostOrIncomplete = $summary.integrity.lostOrIncomplete
        integrityFinalResultMismatch = $summary.integrity.finalResultMismatch
        counts = $summary.counts
        drainSeconds = $staircase.drainSeconds
        traceAlignment = $staircase.traceAlignment
        # Exit 2 is an assertion failure the harness records and keeps. It is carried here so a run
        # that only finished because the bar was lowered cannot sit silently beside a clean one.
        gatlingAssertionFailed = [bool]$summary.events.gatlingAssertionFailed
        apiRateLimitSuspected = [bool]$staircase.apiRateLimitSuspected
        knee = $staircase.capacityKnee.intervalDescription
        # The table cell uses the one-line form: the full description carries the fragility and
        # confound prose, which would run to several sentences inside a single cell.
        kneeShort = if ($null -ne $staircase.capacityKnee.intervalShort) { $staircase.capacityKnee.intervalShort } else { $staircase.capacityKnee.intervalDescription }
        kneeLowerRps = $staircase.capacityKnee.sustainedSteadyUpToRps
        kneeUpperRps = $staircase.capacityKnee.firstOverloadStageRps
        kneeLowerVerdictRobust = $staircase.capacityKnee.sustainedSteadyStageVerdictRobust
        # Rate for the lower end (threshold minus growth), row count for the upper end (net drift
        # minus the rows that would have tripped the threshold). The two are different units and are
        # printed with different labels; naming both RowsPerSec made a 3-row margin read as 3 rows/s.
        kneeLowerMarginRowsPerSec = $staircase.capacityKnee.sustainedSteadyStageMarginRowsPerSec
        kneeUpperVerdictRobust = $staircase.capacityKnee.firstOverloadStageVerdictRobust
        kneeUpperMarginRows = $kneeUpperMarginRows
        stages = $measured
        throughput = $throughput
        # A run that lost or duplicated work cannot be compared on throughput: the number would be
        # describing a pipeline that did not do what the others did.
        excludedFromComparison = (-not $integrityPassed)
        exclusionReason = $integrityReason
    })
}

# A ratio across two runs is only a statement about max-in-flight if everything else was held fixed.
# The fields below are the ones the experiment's common conditions pin, so a difference in any of
# them means the runs are not a controlled pair.
$comparabilityFields = @("stageRps", "stageHoldSeconds", "steadyGuardSeconds", "warmupStageCount",
    "workerCountPerNode", "claimBatchSize", "claimTimeout")
$parameterMismatches = New-Object System.Collections.Generic.List[object]
foreach ($field in $comparabilityFields) {
    $distinct = @($analyzed | ForEach-Object { [string]($_ | Select-Object -ExpandProperty $field) } | Sort-Object -Unique)
    if ($distinct.Count -gt 1) {
        $parameterMismatches.Add([ordered]@{
            field = $field
            values = $distinct
            reason = "runs disagree on $field ($($distinct -join ' vs ')), so a throughput ratio across them would not isolate max-in-flight"
        })
    }
}
$comparabilityBlockedReason = if ($parameterMismatches.Count -gt 0) {
    "the runs are not a controlled pair: " + (@($parameterMismatches | ForEach-Object { $_.reason }) -join "; ")
} else { "" }

# Two runs at the same max-in-flight mean the mapping is ambiguous. Keeping the last one silently
# would drop a repeatability run from the ratios while still printing it in the tables.
$byMif = @{}
# The max-in-flight ratios hold the poll interval fixed: they are built from the runs that share the
# first run's poll interval. Runs at another poll interval are compared in the poll section instead.
$ratioPollInterval = if ($analyzed.Count -gt 0) { [string]$analyzed[0].pollInterval } else { "" }
foreach ($run in $analyzed) {
    if ([string]$run.pollInterval -ne $ratioPollInterval) { continue }
    if ($byMif.ContainsKey($run.maxInFlight)) {
        $previous = $byMif[$run.maxInFlight]
        $excluded.Add([ordered]@{
            runDirectory = $run.runDirectory
            runId = $run.runId
            reason = "another analyzed run ($($previous.runId)) already carries max-in-flight $($run.maxInFlight); ratios use the first, so this run is listed but not ratioed"
        })
        continue
    }
    $byMif[$run.maxInFlight] = $run
}

$ratios = [ordered]@{
    note = "peakAchievedResultRps is the highest single-second result rate seen in any measured stage, so once load exceeds capacity it is the deepest-queueing stage's best second rather than a sustainable rate; highestSteadyResultRps is bounded by the highest ladder rung that held; saturatedResultRpsMedian is the measured plateau and the only one framed as capacity"
    mif16OverMif8 = [ordered]@{
        peakAchievedResultRps = Get-MifRatio $byMif 16 8 "peakAchievedResultRps" $comparabilityBlockedReason
        highestSteadyResultRps = Get-MifRatio $byMif 16 8 "highestSteadyResultRps" $comparabilityBlockedReason
        saturatedResultRpsMedian = Get-MifRatio $byMif 16 8 "saturatedResultRpsMedian" $comparabilityBlockedReason
    }
    mif64OverMif16 = [ordered]@{
        peakAchievedResultRps = Get-MifRatio $byMif 64 16 "peakAchievedResultRps" $comparabilityBlockedReason
        highestSteadyResultRps = Get-MifRatio $byMif 64 16 "highestSteadyResultRps" $comparabilityBlockedReason
        saturatedResultRpsMedian = Get-MifRatio $byMif 64 16 "saturatedResultRpsMedian" $comparabilityBlockedReason
    }
}

# Raw observations for the five hypotheses. This file deliberately records what was seen and does
# not decide anything: the verdicts belong in the report, next to the reasoning that produced them.
$hypotheses = New-Object System.Collections.Generic.List[object]
$mif8 = if ($byMif.ContainsKey(8)) { $byMif[8] } else { $null }
$mif16 = if ($byMif.ContainsKey(16)) { $byMif[16] } else { $null }
$mif64 = if ($byMif.ContainsKey(64)) { $byMif[64] } else { $null }

function Get-WorkerObservation {
    param($Run)
    if ($null -eq $Run) { return [ordered]@{ available = $false; reason = "no analyzed run for this max-in-flight" } }
    $top = [object[]]@($Run.stages | Sort-Object { $_.targetRps } | Select-Object -Last 1)
    $stage = if ($top.Count -gt 0) { $top[0] } else { $null }
    if ($null -eq $stage) { return [ordered]@{ available = $false; reason = "the run has no measured stages" } }
    return [ordered]@{
        available = $true
        topStageLabel = $stage.label
        topStageTargetRps = $stage.targetRps
        judge1RunningMax = $stage.executor.judge1.running.max
        judge1RunningAverage = $stage.executor.judge1.running.average
        judge2RunningMax = $stage.executor.judge2.running.max
        judge2RunningAverage = $stage.executor.judge2.running.average
        bothNodesRunningMax = $stage.executor.bothNodes.running.max
        bothNodesRunningAverage = $stage.executor.bothNodes.running.average
    }
}

function Get-BacklogAndWaitObservation {
    param($Run)
    if ($null -eq $Run) { return [ordered]@{ available = $false; reason = "no analyzed run for this max-in-flight" } }
    $top = [object[]]@($Run.stages | Sort-Object { $_.targetRps } | Select-Object -Last 1)
    $stage = if ($top.Count -gt 0) { $top[0] } else { $null }
    if ($null -eq $stage) { return [ordered]@{ available = $false; reason = "the run has no measured stages" } }
    # The queue-depth gauges are facts about saturation, so the top stage is the right place to read
    # them. Its percentiles are not: the top stage is always the deepest overload, and an overloaded
    # percentile measures how long work waited, not how long the service took.
    $reliable = [object[]]@($Run.stages | Where-Object { $_.reliableAsSteadyStateLatency })
    return [ordered]@{
        available = $true
        topStageLabel = $stage.label
        topStageTargetRps = $stage.targetRps
        topStageClassification = $stage.classification
        bothNodesQueuedMax = $stage.executor.bothNodes.queued.max
        bothNodesQueuedAverage = $stage.executor.bothNodes.queued.average
        bothNodesReservedMax = $stage.executor.bothNodes.reserved.max
        bothNodesReservedAverage = $stage.executor.bothNodes.reserved.average
        runDrainSeconds = $Run.drainSeconds
        queueingP95Ms = $stage.latency.L_total_ms.p95
        queueingP99Ms = $stage.latency.L_total_ms.p99
        queueingMaxMs = $stage.latency.L_total_ms.max
        queueingPercentileBasis = "$($stage.label) is $($stage.classification), so these are wait times under overload and are not service latency"
        reliableStageCount = $reliable.Count
        serviceP95Ms = Get-Maximum @($reliable | ForEach-Object { $_.latency.L_total_ms.p95 })
        serviceP99Ms = Get-Maximum @($reliable | ForEach-Object { $_.latency.L_total_ms.p99 })
        servicePercentileBasis = if ($reliable.Count -gt 0) {
            "the largest p95/p99 among this run's stages the analyzer certifies as steady-state service time: " + (@($reliable | ForEach-Object { $_.label }) -join ', ')
        } else { "this run has no stage the analyzer certifies as steady-state service time" }
    }
}

# The duplicate-work hypothesis is also the place to state what the run never did: a zero reclaim
# total only means something next to the claim attempts it was drawn from.
function Get-DuplicateObservation {
    param($Run)
    if ($null -eq $Run) { return [ordered]@{ available = $false; reason = "no analyzed run for this max-in-flight" } }
    return [ordered]@{
        available = $true
        staleReclaimRowsTotal = Get-Sum @($Run.stages | ForEach-Object { $_.claim.staleReclaimRowsInWindow })
        stagesWithStaleReclaims = [object[]]@($Run.stages | Where-Object { $_.claim.staleReclaimRowsInWindow -gt 0 } | ForEach-Object {
            [ordered]@{ label = $_.label; targetRps = $_.targetRps; rows = $_.claim.staleReclaimRowsInWindow; claimStaleDelta = $_.claim.claimStaleDelta }
        })
        staleCompletionDeltaTotal = Get-Sum @($Run.stages | ForEach-Object { $_.claim.staleCompletionDelta })
        claimStaleDeltaTotal = Get-Sum @($Run.stages | ForEach-Object { $_.claim.claimStaleDelta })
        duplicateJudgeMillisLowerBoundTotal = Get-Sum @($Run.stages | ForEach-Object { $_.claim.duplicateJudgementMillisLowerBound })
        duplicateJudgeMillisUpperBoundTotal = Get-Sum @($Run.stages | ForEach-Object { $_.claim.duplicateJudgementMillisUpperBound })
        executorRejectionsTotal = Get-Sum @($Run.stages | ForEach-Object { $_.claim.executorRejectionsDelta })
    }
}

$hypotheses.Add([ordered]@{
    hypothesis = "max-in-flight below the worker count limits how many of the 16 workers can run at once"
    observation = "per-node running gauge at each run's top stage"
    values = [ordered]@{ maxInFlight8 = Get-WorkerObservation $mif8; maxInFlight16 = Get-WorkerObservation $mif16; maxInFlight64 = Get-WorkerObservation $mif64 }
})
$hypotheses.Add([ordered]@{
    hypothesis = "max-in-flight equal to the worker count is the smallest value that lets every worker run"
    observation = "per-node running gauge and achieved result rate at each run's top stage"
    values = [ordered]@{
        maxInFlight8 = Get-WorkerObservation $mif8
        maxInFlight16 = Get-WorkerObservation $mif16
        achievedResultRpsCeiling = [ordered]@{
            maxInFlight8 = if ($null -ne $mif8) { $mif8.throughput.peakAchievedResultRps } else { $null }
            maxInFlight16 = if ($null -ne $mif16) { $mif16.throughput.peakAchievedResultRps } else { $null }
        }
    }
})
$hypotheses.Add([ordered]@{
    hypothesis = "raising max-in-flight well above the worker count does not raise the throughput ceiling much"
    observation = "saturated result rate median over overloaded stages, plus the best-single-second and highest-steady-rung fields for contrast"
    values = [ordered]@{
        maxInFlight16 = if ($null -ne $mif16) { $mif16.throughput } else { $null }
        maxInFlight64 = if ($null -ne $mif64) { $mif64.throughput } else { $null }
        ratio = $ratios.mif64OverMif16
    }
})
$hypotheses.Add([ordered]@{
    hypothesis = "a large max-in-flight absorbs bursts but leaves more work queued and reserved, with longer tails and a longer drain"
    observation = "queued/reserved gauges and run-level drain from each run's top stage, with queue wait percentiles and steady-state service percentiles kept in separate fields"
    values = [ordered]@{ maxInFlight16 = Get-BacklogAndWaitObservation $mif16; maxInFlight64 = Get-BacklogAndWaitObservation $mif64 }
})
$hypotheses.Add([ordered]@{
    hypothesis = "with a 30s claim timeout, steady state should reclaim almost nothing"
    observation = "stale reclaim rows and stale-token completions per stage, from the durable outbox and the node counters"
    values = [ordered]@{ maxInFlight16 = Get-DuplicateObservation $mif16; maxInFlight64 = Get-DuplicateObservation $mif64 }
})

# Poll interval comparison: every analyzed run at its own (max-in-flight, poll) condition, and for each
# max-in-flight every pair of poll intervals, ratioed on the saturated plateau. Within one condition
# the first run in execution order is the one ratioed; the others are listed as repeats.
$pollConditions = [ordered]@{}
foreach ($run in $analyzed) {
    $key = "$($run.maxInFlight)|$($run.pollInterval)"
    if (-not $pollConditions.Contains($key)) { $pollConditions[$key] = New-Object System.Collections.Generic.List[object] }
    $pollConditions[$key].Add($run)
}
$pollRuns = [object[]]@($analyzed | Sort-Object @{ Expression = { $_.maxInFlight } }, @{ Expression = { - [double]$(if ($null -eq $_.pollIntervalMillis) { 0 } else { $_.pollIntervalMillis }) } } | ForEach-Object {

    [ordered]@{
        runId = $_.runId
        pollInterval = $_.pollInterval
        maxInFlight = $_.maxInFlight
        excludedFromComparison = $_.excludedFromComparison
        saturatedResultRpsMedian = $_.throughput.saturatedResultRpsMedian
        saturatedStageCount = $_.throughput.saturatedStageCount
        percentOfTheory = if ($null -ne $_.throughput.saturatedResultRpsMedian) { [math]::Round(100.0 * $_.throughput.saturatedResultRpsMedian / $TheoryResultRps, 1) } else { $null }
        knee = $_.kneeShort
        drainSeconds = $_.drainSeconds
        idleClaimCallsPerSecond = if ($null -ne $_.idleBaseline) { $_.idleBaseline.claimCallsPerSecond } else { $null }
        idleQuestionsPerSecond = if ($null -ne $_.idleBaseline) { $_.idleBaseline.mysql.QuestionsPerSecond } else { $null }
        idleMysqlCpuCores = if ($null -ne $_.idleBaseline -and $null -ne $_.idleBaseline.containerCpu -and $null -ne $_.idleBaseline.containerCpu.'oj-loadtest-mysql') { $_.idleBaseline.containerCpu.'oj-loadtest-mysql'.meanCores } else { $null }
    }
})
$pollRatios = New-Object System.Collections.Generic.List[object]
foreach ($mif in @($analyzed | ForEach-Object { $_.maxInFlight } | Sort-Object -Unique)) {
    $atMif = @($pollConditions.Keys | Where-Object { $_ -like "$mif|*" })
    foreach ($numeratorKey in $atMif) {
        foreach ($denominatorKey in $atMif) {
            if ($numeratorKey -eq $denominatorKey) { continue }
            $numerator = $pollConditions[$numeratorKey][0]
            $denominator = $pollConditions[$denominatorKey][0]
            # Faster poll over slower poll only, so each pair appears once and reads as "what shortening bought".
            if ($null -eq $numerator.pollIntervalMillis -or $null -eq $denominator.pollIntervalMillis -or $numerator.pollIntervalMillis -ge $denominator.pollIntervalMillis) { continue }
            $blocked = if ($numerator.excludedFromComparison) { "$($numerator.runId): $($numerator.exclusionReason)" } elseif ($denominator.excludedFromComparison) { "$($denominator.runId): $($denominator.exclusionReason)" } else { $null }
            $nValue = $numerator.throughput.saturatedResultRpsMedian
            $dValue = $denominator.throughput.saturatedResultRpsMedian
            $pollRatios.Add([ordered]@{
                maxInFlight = $mif
                numeratorRunId = $numerator.runId
                numeratorPollInterval = $numerator.pollInterval
                denominatorRunId = $denominator.runId
                denominatorPollInterval = $denominator.pollInterval
                numeratorSaturatedResultRps = $nValue
                denominatorSaturatedResultRps = $dValue
                ratio = if ($null -eq $blocked -and $null -ne $nValue -and $null -ne $dValue -and $dValue -ne 0) { [math]::Round($nValue / $dValue, 3) } else { $null }
                blockedReason = $blocked
            })
        }
    }
}

$comparison = [ordered]@{
    generatedAt = [datetimeoffset]::UtcNow.ToString("o")
    pollComparison = [ordered]@{
        theoryResultRps = [math]::Round($TheoryResultRps, 3)
        theoryBasis = "2 nodes x 16 workers / 147.5ms mean synthetic judge time; a reference, not a fitting target"
        runs = $pollRuns
        ratios = [object[]]$pollRatios
        maxInFlightRatiosUsePollInterval = $ratioPollInterval
    }
    runOrder = [object[]]@($analyzed | ForEach-Object { $_.runId })
    runs = [object[]]$analyzed
    ratios = $ratios
    hypotheses = [object[]]$hypotheses
    excluded = [object[]]$excluded
    comparability = [ordered]@{
        controlledPair = ($parameterMismatches.Count -eq 0)
        blockedReason = $comparabilityBlockedReason
        parametersChecked = $comparabilityFields
        mismatches = [object[]]$parameterMismatches
    }
    note = "raw observations only; no hypothesis is accepted or rejected here"
}
$outputRoot = if ($OutputDirectory) { (Resolve-Path $OutputDirectory).Path } else { Split-Path -Parent (Resolve-Path $RunDirectory[0]).Path }
$comparisonPath = Join-Path $outputRoot "comparison.json"
$comparison | ConvertTo-Json -Depth 12 | Set-Content $comparisonPath -Encoding utf8

$lines = @(
    "# max-in-flight capacity comparison", "",
    "- Runs in execution order: $((@($analyzed | ForEach-Object { "$($_.runId) (max-in-flight $($_.maxInFlight))" })) -join ', ')",
    "- Excluded: $(if ($excluded.Count -eq 0) { 'none' } else { (@($excluded | ForEach-Object { "$($_.runDirectory): $($_.reason)" })) -join '; ' })",
    "- Controlled pair: $(if ($parameterMismatches.Count -eq 0) { 'yes, every pinned parameter matches across the analyzed runs' } else { "no - $comparabilityBlockedReason" })",
    "",
    "## Per-run summary", "",
    "| Run | max-in-flight | claim batch | timeout | integrity | assertion failed | drain s | trace alignment | saturated result RPS (median) | knee | steady up to RPS | knee lower verdict robust | first overload RPS | first overload verdict robust | API 429 suspected |",
    "|---|---:|---:|---|---|---|---|---:|---|---|---:|---|---:|---|---|"
)
foreach ($run in $analyzed) {
    $lines += "| $($run.runId) | $($run.maxInFlight) | $($run.claimBatchSize) | $($run.claimTimeout) | " +
        "$(if ($run.integrityPassed) { 'passed' } else { "failed ($($run.exclusionReason))" }) | " +
        "$(if ($run.gatlingAssertionFailed) { 'yes' } else { 'no' }) | " +
        "$(if ($null -eq $run.drainSeconds) { 'unavailable' } else { $run.drainSeconds }) | $($run.traceAlignment) | " +
        "$(if ($null -eq $run.throughput.saturatedResultRpsMedian) { 'unavailable' } else { "$($run.throughput.saturatedResultRpsMedian) over $($run.throughput.saturatedStageCount) overloaded stages" }) | " +
        "$($run.kneeShort) | " +
        "$(if ($null -eq $run.kneeLowerRps) { 'unavailable' } else { $run.kneeLowerRps }) | " +
        "$(if ($null -eq $run.kneeLowerVerdictRobust) { 'unavailable' } elseif ($run.kneeLowerVerdictRobust) { 'yes' } elseif ($null -eq $run.kneeLowerMarginRowsPerSec) { 'no (margin unavailable)' } else { "no (margin $($run.kneeLowerMarginRowsPerSec) rows/s)" }) | " +
        "$(if ($null -eq $run.kneeUpperRps) { 'unavailable' } else { $run.kneeUpperRps }) | " +
        "$(if ($null -eq $run.kneeUpperVerdictRobust) { 'unavailable' } elseif ($run.kneeUpperVerdictRobust) { 'yes' } elseif ($null -eq $run.kneeUpperMarginRows) { 'no (margin unavailable)' } else { "no (margin $($run.kneeUpperMarginRows) rows)" }) | " +
        "$(if ($run.apiRateLimitSuspected) { 'yes' } else { 'no' }) |"
}
$lines += @("", "A 'no' in either robustness column means that end of the knee is steady or overloaded only under the exact threshold used here: halving or doubling the threshold, or dropping one end sample, changes the verdict. Read such a run's interval as the neighbourhood of a transition rather than as two proven operating points.")

$lines += @("", "## Poll interval comparison", "",
    "Theory reference: $([math]::Round($TheoryResultRps, 1)) result/s (2 nodes x 16 workers / 147.5ms). The max-in-flight ratios below use only the runs at poll interval $ratioPollInterval.", "",
    "| Run | poll | max-in-flight | saturated result RPS (median of overloaded stages) | % of theory | knee | drain s | idle claims/s | idle Questions/s | idle MySQL CPU cores |",
    "|---|---|---:|---:|---:|---|---:|---:|---:|---:|")
foreach ($entry in $pollRuns) {
    $lines += "| $($entry.runId) | $($entry.pollInterval) | $($entry.maxInFlight) | " +
        "$(if ($null -eq $entry.saturatedResultRpsMedian) { 'unavailable' } else { "$($entry.saturatedResultRpsMedian) ($($entry.saturatedStageCount) stages)" }) | " +
        "$(if ($null -eq $entry.percentOfTheory) { 'unavailable' } else { $entry.percentOfTheory }) | $($entry.knee) | " +
        "$(if ($null -eq $entry.drainSeconds) { 'unavailable' } else { $entry.drainSeconds }) | " +
        "$(if ($null -eq $entry.idleClaimCallsPerSecond) { 'unavailable' } else { $entry.idleClaimCallsPerSecond }) | " +
        "$(if ($null -eq $entry.idleQuestionsPerSecond) { 'unavailable' } else { $entry.idleQuestionsPerSecond }) | " +
        "$(if ($null -eq $entry.idleMysqlCpuCores) { 'unavailable' } else { $entry.idleMysqlCpuCores }) |"
}
$lines += @("", "| max-in-flight | faster poll run | slower poll run | saturated RPS faster / slower | ratio | note |", "|---:|---|---|---|---:|---|")
foreach ($ratio in $pollRatios) {
    $lines += "| $($ratio.maxInFlight) | $($ratio.numeratorRunId) ($($ratio.numeratorPollInterval)) | $($ratio.denominatorRunId) ($($ratio.denominatorPollInterval)) | " +
        "$($ratio.numeratorSaturatedResultRps) / $($ratio.denominatorSaturatedResultRps) | $(if ($null -eq $ratio.ratio) { 'unavailable' } else { $ratio.ratio }) | $(if ($ratio.blockedReason) { $ratio.blockedReason } else { '' }) |"
}

$lines += @("", "## Database cost per stage, all runs", "",
    "| Run | poll | max-in-flight | Stage | target RPS | result RPS | running avg /$(2 * 16) | queued avg | claims/s | rows/claim | Questions/s | row-lock waits/s | MySQL CPU cores |",
    "|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($run in $analyzed) {
    if ($null -ne $run.idleBaseline) {
        $idleCpu = if ($null -ne $run.idleBaseline.containerCpu -and $null -ne $run.idleBaseline.containerCpu.'oj-loadtest-mysql') { $run.idleBaseline.containerCpu.'oj-loadtest-mysql'.meanCores } else { 'unavailable' }
        $lines += "| $($run.runId) | $($run.pollInterval) | $($run.maxInFlight) | idle (no load) | 0 | 0 | 0 | 0 | " +
            "$(if ($null -eq $run.idleBaseline.claimCallsPerSecond) { 'unavailable' } else { $run.idleBaseline.claimCallsPerSecond }) | 0 | " +
            "$(if ($null -eq $run.idleBaseline.mysql.QuestionsPerSecond) { 'unavailable' } else { $run.idleBaseline.mysql.QuestionsPerSecond }) | " +
            "$(if ($null -eq $run.idleBaseline.mysql.Innodb_row_lock_waitsPerSecond) { 'unavailable' } else { $run.idleBaseline.mysql.Innodb_row_lock_waitsPerSecond }) | $idleCpu |"
    }
    foreach ($stage in $run.stages) {
        $mysqlCpu = if ($null -ne $stage.mysql.containerCpu -and $null -ne $stage.mysql.containerCpu.'oj-loadtest-mysql') { $stage.mysql.containerCpu.'oj-loadtest-mysql'.meanCores } else { 'unavailable' }
        $lines += "| $($run.runId) | $($run.pollInterval) | $($run.maxInFlight) | $($stage.label) | $($stage.targetRps) | " +
            "$(if ($null -eq $stage.resultsCompleted.perSecond) { 'unavailable' } else { $stage.resultsCompleted.perSecond }) | " +
            "$($stage.executor.bothNodes.running.average) | $($stage.executor.bothNodes.queued.average) | " +
            "$(if ($null -eq $stage.mechanism.claimsPerSecond) { 'unavailable' } else { $stage.mechanism.claimsPerSecond }) | " +
            "$(if ($null -eq $stage.mechanism.rowsPerClaim) { 'unavailable' } else { $stage.mechanism.rowsPerClaim }) | " +
            "$(if ($null -eq $stage.mysql.questionsPerSecond) { 'unavailable' } else { $stage.mysql.questionsPerSecond }) | " +
            "$(if ($null -eq $stage.mysql.rowLockWaitsPerSecond) { 'unavailable' } else { $stage.mysql.rowLockWaitsPerSecond }) | $mysqlCpu |"
    }
}

$lines += @("", "## Throughput ratios", "",
    "- $($ratios.note)",
    "",
    "| Comparison | Field | Numerator | Denominator | Ratio | Note |",
    "|---|---|---:|---:|---:|---|")
foreach ($group in @("mif16OverMif8", "mif64OverMif16")) {
    foreach ($field in @("saturatedResultRpsMedian", "peakAchievedResultRps", "highestSteadyResultRps")) {
        $entry = $ratios[$group][$field]
        $lines += "| $group | $($entry.field) | $(if ($null -eq $entry.numeratorValue) { 'unavailable' } else { $entry.numeratorValue }) | " +
            "$(if ($null -eq $entry.denominatorValue) { 'unavailable' } else { $entry.denominatorValue }) | " +
            "$(if ($null -eq $entry.ratio) { 'unavailable' } else { $entry.ratio }) | $(if ($entry.available) { $(if ($entry.ratio -gt 1.05) { 'numerator higher' } elseif ($entry.ratio -lt 0.95) { 'numerator lower' } else { 'within 5 percent' }) } else { $entry.reason }) |"
    }
}

$lines += @("", "## Stage table, all runs", "",
    "| Run | max-in-flight | Stage | target RPS | class | result RPS | accepted RPS | judge backlog rows/s | scoreboard backlog rows/s | p95 L_total ms | p99 L_total ms | p95/p99 readable as service latency | running avg (both) | queued avg (both) | reserved avg (both) | OK | 429 | 503 | success % |",
    "|---|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|")
foreach ($run in $analyzed) {
    foreach ($stage in $run.stages) {
        $lines += "| $($run.runId) | $($run.maxInFlight) | $($stage.label) | $($stage.targetRps) | $($stage.classification) | " +
            "$(if ($null -eq $stage.resultsCompleted.perSecond) { 'unavailable' } else { $stage.resultsCompleted.perSecond }) | " +
            "$(if ($null -eq $stage.accepted.perSecond) { 'unavailable' } else { $stage.accepted.perSecond }) | " +
            "$(if ($null -eq $stage.backlogGrowth.judgeRowsPerSec) { 'unavailable' } else { $stage.backlogGrowth.judgeRowsPerSec }) | " +
            "$(if ($null -eq $stage.backlogGrowth.scoreboardRowsPerSec) { 'unavailable' } else { $stage.backlogGrowth.scoreboardRowsPerSec }) | " +
            "$($stage.latency.L_total_ms.p95) | $($stage.latency.L_total_ms.p99) | " +
            # Only an overloaded stage is excluded *because* its percentiles measure queueing. A
            # steady stage can be excluded for a different reason - a verdict that does not survive
            # the robustness probes is the one that actually occurs here - and labelling that row
            # "queueing" would tell the reader an overload happened where the backlog was flat.
            "$(if ($stage.reliableAsSteadyStateLatency) { 'yes' } elseif ($stage.overloadQueueingResults) { 'no - queueing' } else { 'no - see note' }) | " +
            "$($stage.executor.bothNodes.running.average) | $($stage.executor.bothNodes.queued.average) | $($stage.executor.bothNodes.reserved.average) | " +
            "$(if ($null -eq $stage.http.ok) { 'unavailable' } else { $stage.http.ok }) | " +
            "$(if ($null -eq $stage.http.ko429) { 'unavailable' } else { $stage.http.ko429 }) | " +
            "$(if ($null -eq $stage.http.ko503) { 'unavailable' } else { $stage.http.ko503 }) | " +
            "$(if ($null -eq $stage.http.successPercent) { 'unavailable' } else { $stage.http.successPercent }) |"
    }
}
$unreadable = [object[]]@($analyzed | ForEach-Object { $_.stages } | Where-Object { -not $_.reliableAsSteadyStateLatency })
$unreadableQueueing = [object[]]@($unreadable | Where-Object { $_.overloadQueueingResults })
# One run's stage-1 and another run's stage-1 share a label, so the run id has to travel with the
# reason or the footnote cannot say which row it is explaining. `$run` is read into a named local
# rather than relying on `$_` inside the inner pipeline, which would be the stage, not the run.
$unreadableOther = [object[]]@($analyzed | ForEach-Object {
    $run = $_
    @($run.stages | Where-Object { -not $_.reliableAsSteadyStateLatency -and -not $_.overloadQueueingResults } | ForEach-Object {
        [pscustomobject]@{
            runId = $run.runId
            label = $_.label
            targetRps = $_.targetRps
            classification = $_.classification
            reason = $_.reliableAsSteadyStateLatencyReason
        }
    })
})
if ($unreadableQueueing.Count -gt 0) {
    $lines += @("", "A p95/p99 in a row marked 'no - queueing' is a wait time under overload; it is not service latency and is not evidence about max-in-flight. Stages marked 'yes' on the same run are the ones whose percentiles may be compared as service time.")
}
if ($unreadableOther.Count -gt 0) {
    $lines += @("", "A row marked 'no - see note' is not readable as service latency either, but not because of overload queueing - its stage held a flat backlog and the exclusion has another cause, given here per run:")
    foreach ($entry in $unreadableOther) {
        $lines += "- $($entry.runId) $($entry.label) ($($entry.targetRps) RPS, $($entry.classification)): $($entry.reason)"
    }
}

$lines += @("", "## Hypothesis observations (no verdicts here)", "")
foreach ($hypothesis in $hypotheses) {
    $lines += @("### $($hypothesis.hypothesis)", "", $hypothesis.observation, "")
    foreach ($key in @($hypothesis.values.Keys)) {
        $lines += "- ${key}: $($hypothesis.values[$key] | ConvertTo-Json -Depth 6 -Compress)"
    }
    $lines += ""
}
$lines += @("", "Raw observations only; no hypothesis is accepted or rejected by this file.")
$lines | Set-Content (Join-Path $outputRoot "comparison.md") -Encoding utf8

Write-Host "Wrote comparison.json and comparison.md to $outputRoot"
# An explicit success exit code, so a caller that chains this after the analyzer can tell a
# completed comparison from a script that ended without ever setting one.
exit 0
