[CmdletBinding()]
param(
    # Normal-timeout run directories in the order they were executed, each already analyzed by
    # Analyze-TradeoffRun.ps1 so that summary.json carries the duplication section.
    [Parameter(Mandatory = $true)][string[]]$RunDirectory,
    # Defaults to the folder that holds the runs, so the comparison lands beside them. The file names
    # are specific to this experiment so that running it cannot overwrite the staircase comparison.
    [string]$OutputDirectory = "",
    [ValidatePattern('^[A-Za-z0-9._-]+$')][string]$OutputBaseName = "normal-timeout-comparison"
)

$ErrorActionPreference = "Stop"

# Raw observations only. The six-run table is a comparison, not a verdict: which timeout is
# supportable is argued in docs/MYSQL_JUDGE_NORMAL_TIMEOUT_DUPLICATION.md, from these numbers.
$runs = New-Object System.Collections.Generic.List[object]
$excluded = New-Object System.Collections.Generic.List[object]

foreach ($directory in $RunDirectory) {
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
    $parametersPath = Join-Path $runPath "parameters.json"
    $parameters = if (Test-Path $parametersPath) { Get-Content $parametersPath -Raw | ConvertFrom-Json } else { $null }
    if ($null -eq $summary.duplication) {
        $excluded.Add([ordered]@{
            runDirectory = $runPath; runId = $summary.runId
            reason = "the summary carries no duplication section; it is not a normal-timeout run (or predates the normal-timeout harness)"
        })
        continue
    }
    if ($null -eq $parameters) {
        $excluded.Add([ordered]@{ runDirectory = $runPath; runId = $summary.runId; reason = "parameters.json is missing, so max-in-flight and claim timeout cannot be read" })
        continue
    }

    $duplication = $summary.duplication
    $stage = @($summary.staircase.stages | Where-Object { $_.label -eq $duplication.measuredStage })
    $stage = if ($stage.Count -gt 0) { $stage[0] } else { $null }
    $steady = ($stage.reliableAsSteadyStateLatency -eq $true)
    # The percentiles in the table are the measured window's, and a window whose backlog grew is
    # queueing rather than service time. The value is still published - hiding it would hide the
    # evidence - but it carries the marker so it cannot be read as a service latency.
    $latencyMarker = if ($steady) { "" } else { " (queueing)" }

    $integrityCountNames = @("accepted", "uniqueSubmissions", "results", "scoreboardApplied")
    $missingCounts = @($integrityCountNames | Where-Object { $null -eq $summary.counts.$_ -or [double]$summary.counts.$_ -le 0 })
    $integrityPassed = [bool]$summary.integrity.passed -and $missingCounts.Count -eq 0
    $integrityReason = if ($missingCounts.Count -gt 0) {
        "verification counts are missing or zero for $($missingCounts -join ', '), so the integrity check could not confirm the run"
    } elseif (-not [bool]$summary.integrity.passed) {
        "integrity failed: lostOrIncomplete=$($summary.integrity.lostOrIncomplete), finalResultMismatch=$($summary.integrity.finalResultMismatch)"
    } else { $null }

    $dc = $duplication.durableDuplicateClaim
    $dj = $duplication.actualDuplicateJudgement
    $tf = $duplication.tokenFencing
    $runs.Add([ordered]@{
        runId = $summary.runId
        runDirectory = $runPath
        mysqlMaxInFlightPerNode = [int]$parameters.mysqlMaxInFlightPerNode
        mysqlClaimBatchSize = [int]$parameters.mysqlClaimBatchSize
        mysqlClaimTimeout = $parameters.mysqlClaimTimeout
        workerCountPerNode = [int]$parameters.workerCountPerNode
        targetRps = $duplication.targetRps
        measuredStage = $duplication.measuredStage
        measurementWindowSeconds = $duplication.measurementWindowSeconds
        accepted = $summary.counts.accepted
        uniqueSubmissions = $summary.counts.uniqueSubmissions
        results = $summary.counts.results
        scoreboardApplied = $summary.counts.scoreboardApplied
        completedHttpRequests = $summary.counts.completedHttpRequests
        scheduledRequests = if ($null -ne $stage) { [math]::Round([double]$duplication.targetRps * [double]$stage.measurementSeconds, 0) } else { $null }
        completedWindowRequests = if ($null -ne $stage) { $stage.http.offered } else { $null }
        resultPerSecond = if ($null -ne $stage) { $stage.resultsCompleted.perSecond } else { $null }
        acceptedPerSecond = if ($null -ne $stage) { $stage.accepted.perSecond } else { $null }
        scoreboardAppliedPerSecond = if ($null -ne $stage) { $stage.scoreboardApplied.perSecond } else { $null }
        backlogGrowthRowsPerSec = if ($null -ne $stage) { $stage.backlogGrowth.totalRowsPerSec } else { $null }
        backlogGrowthSecondHalfRowsPerSec = if ($null -ne $stage) { $stage.backlogByHalf.secondHalfRowsPerSec } else { $null }
        backlogStart = if ($null -ne $stage) { $stage.backlogGrowth.totalStart } else { $null }
        backlogEnd = if ($null -ne $stage) { $stage.backlogGrowth.totalEnd } else { $null }
        backlogPeak = if ($null -ne $stage) { $stage.backlogGrowth.totalPeak } else { $null }
        classification = if ($null -ne $stage) { $stage.classification } else { $null }
        growthVerdictRobust = if ($null -ne $stage) { $stage.growthRobustness.stable } else { $null }
        p50TotalMs = if ($null -ne $stage) { $stage.latency.L_total_ms.p50 } else { $null }
        p95TotalMs = if ($null -ne $stage) { $stage.latency.L_total_ms.p95 } else { $null }
        p99TotalMs = if ($null -ne $stage) { $stage.latency.L_total_ms.p99 } else { $null }
        p95ResultMs = if ($null -ne $stage) { $stage.latency.L_result_ms.p95 } else { $null }
        p95ScoreboardMs = if ($null -ne $stage) { $stage.latency.L_scoreboard_ms.p95 } else { $null }
        fastLatency = if ($null -ne $stage) { $stage.latencyByClass.fast } else { $null }
        slowLatency = if ($null -ne $stage) { $stage.latencyByClass.slow } else { $null }
        measurementJudgeWorkByLatencyClass = if ($null -ne $stage) { $stage.judgeWorkByLatencyClass } else { $null }
        latencyClassAccounting = $duplication.latencyClassAccounting
        latencyReadableAsServiceTime = $steady
        latencyMarker = $latencyMarker
        drainSeconds = $summary.events.drainSeconds
        ko429 = if ($null -ne $stage) { $stage.http.ko429 } else { $null }
        ko503 = if ($null -ne $stage) { $stage.http.ko503 } else { $null }
        ko500 = if ($null -ne $stage) { $stage.http.ko500 } else { $null }
        apiRateLimitPolluted = if ($null -ne $stage) { $stage.apiRateLimitPolluted } else { $null }
        executorCaps = if ($null -ne $stage) { $stage.executorCaps } else { $null }
        executor = if ($null -ne $stage) { $stage.executor } else { $null }
        claim = if ($null -ne $stage) { $stage.claim } else { $null }
        duplicateClaims = $dc.count
        duplicateClaimsPer10kAccepted = $dc.per10kAccepted
        duplicateClaimRatePerAccepted = $dc.ratePerAccepted
        attemptsHistogram = $dc.attemptsHistogram
        duplicateJudgeExecutions = $dj.duplicateJudgeExecutions
        duplicateJudgeExecutionsPer10kAccepted = $dj.per10kAccepted
        judgeInvocations = $dj.judgeInvocations
        judgeInvocationsMinusResults = $dj.judgeInvocationsMinusResults
        failedExecutions = $dj.failedExecutions
        storedResultRepublishes = $dj.storedResultRepublishes
        accountingResidual = $dj.accountingResidual
        duplicateJudgeMillisLowerBound = $dj.duplicateJudgeMillisLowerBound
        duplicateJudgeMillisUpperBound = $dj.duplicateJudgeMillisUpperBound
        staleTokenCompletions = $tf.staleTokenCompletions
        claimStaleObservations = $tf.claimStaleObservations
        warmupQuiescedBeforeBaseline = $duplication.steadyStateCriteria.warmupQuiescedBeforeBaseline
        warmupAcceptedGrowthAfterBaseline = $duplication.warmupExclusion.acceptedGrowthAfterBaseline
        steadyStateQualified = [bool]$duplication.steadyStateQualified
        steadyStateFailedCriteria = [object[]]$duplication.failedCriteria
        integrityPassed = $integrityPassed
        integrityReason = $integrityReason
        # Two different disqualifications, kept apart: a run that is not a valid measurement at all
        # (integrity) and a run that is valid but was not a steady state (persistent backlog growth
        # or refusals). Only the first is dropped from the table; the second is reported and flagged.
        excludedFromTable = (-not $integrityPassed)
        flaggedNotSteady = ($integrityPassed -and -not [bool]$duplication.steadyStateQualified)
    })
}

# Grouped by max-in-flight, in the order the runs were executed. The two groups answer different
# questions and are never averaged together.
$byMif = @{}
foreach ($run in $runs) {
    if (-not $byMif.ContainsKey($run.mysqlMaxInFlightPerNode)) { $byMif[$run.mysqlMaxInFlightPerNode] = New-Object System.Collections.Generic.List[object] }
    $byMif[$run.mysqlMaxInFlightPerNode].Add($run)
}

function Format-Value {
    param($Value)
    if ($null -eq $Value) { return "unavailable" }
    if ($Value -is [bool]) { return $(if ($Value) { "yes" } else { "no" }) }
    return [string]$Value
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

# Resolving the output path would fail on a directory that does not exist yet, which made the one
# natural way to run this - point it at a fresh folder - an error. The default stays the first
# run's own directory, so the comparison lands next to the artifacts it was read from.
$outputRoot = if ($OutputDirectory) {
    if (-not (Test-Path $OutputDirectory)) { New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null }
    (Resolve-Path $OutputDirectory).Path
} else { Split-Path -Parent (Resolve-Path $RunDirectory[0]).Path }
$comparisonPath = Join-Path $outputRoot "$OutputBaseName.json"
$markdownPath = Join-Path $outputRoot "$OutputBaseName.md"

$comparison = [ordered]@{
    generatedAt = [datetimeoffset]::UtcNow.ToString("o")
    runOrder = [object[]]@($runs | ForEach-Object { $_.runId })
    runs = [object[]]$runs
    groups = [ordered]@{}
    excluded = [object[]]$excluded
}
foreach ($mif in @($byMif.Keys | Sort-Object)) {
    $comparison.groups["mif=$mif"] = [object[]]$byMif[$mif]
}
$comparison | ConvertTo-Json -Depth 10 | Set-Content $comparisonPath -Encoding utf8

$lines = @(
    "# MySQL claim timeout comparison", "",
    "- Runs in execution order: $((@($runs | ForEach-Object { $_.runId })) -join ' -> ')",
    "- Excluded: $(if ($excluded.Count -eq 0) { 'none' } else { (@($excluded | ForEach-Object { "$($_.runDirectory): $($_.reason)" })) -join '; ' })",
    "",
    "Raw observations only; no timeout is recommended here. `duplicate claims` is the durable outbox counter (attempts > 1), `duplicate judgements` is the number of judgeSubmission calls beyond one per submission, and the two are different quantities: a reclaimed row is only judged again if the earlier attempt is still running when it is reclaimed. A `(queueing)` marker on a percentile means that window's backlog grew, so the percentile measures the queue and not service time.",
    "",
    "| MIF | target RPS | timeout | accepted | result RPS | backlog growth | duplicate claims | duplicate claims/10k | judge invocations | duplicate judgements | stale token completions | p95 total | p99 total | drain |",
    "|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
)
foreach ($run in $runs) {
    if ($run.excludedFromTable) {
        # A run whose integrity failed is not a measurement of anything, so it is listed with its
        # reason instead of contributing numbers that would read like results.
        $lines += "| $(Format-Value $run.mysqlMaxInFlightPerNode) | $(Format-Value $run.targetRps) | $(Format-Value $run.mysqlClaimTimeout) | excluded: $($run.integrityReason) |  |  |  |  |  |  |  |  |  |  |"
        continue
    }
    $lines += "| $(Format-Value $run.mysqlMaxInFlightPerNode) | $(Format-Value $run.targetRps) | $(Format-Value $run.mysqlClaimTimeout) | " +
        "$(Format-Value $run.accepted) | $(Format-Value $run.resultPerSecond) | $(Format-Value $run.backlogGrowthRowsPerSec) | " +
        "$(Format-Value $run.duplicateClaims) | $(Format-Value $run.duplicateClaimsPer10kAccepted) | " +
        "$(Format-Value $run.judgeInvocations) | $(Format-Value $run.duplicateJudgeExecutions) | $(Format-Value $run.staleTokenCompletions) | " +
        "$(Format-Value $run.p95TotalMs)$($run.latencyMarker) | $(Format-Value $run.p99TotalMs)$($run.latencyMarker) | $(Format-Value $run.drainSeconds) |"
}

$lines += @(
    "", "## Run inventory", "",
    "| # | run id | MIF | timeout | integrity | steady state qualified | failed criteria | executor within caps | warm-up quiesced | warm-up accepted growth after baseline |",
    "|---:|---|---:|---|---|---|---|---|---|---:|"
)
$index = 0
foreach ($run in $runs) {
    $index++
    $lines += "| $index | $($run.runId) | $(Format-Value $run.mysqlMaxInFlightPerNode) | $(Format-Value $run.mysqlClaimTimeout) | " +
        "$(if ($run.integrityPassed) { 'passed' } else { 'FAILED' }) | " +
        "$(if ($run.steadyStateQualified) { 'yes' } else { 'no' }) | " +
        "$(if (@($run.steadyStateFailedCriteria).Count -eq 0) { '-' } else { (@($run.steadyStateFailedCriteria) -join ', ') }) | " +
        "$(Format-Value $run.executorCaps.withinConfiguredCaps) | $(Format-Value $run.warmupQuiescedBeforeBaseline) | $(Format-Value $run.warmupAcceptedGrowthAfterBaseline) |"
}

$lines += @(
    "", "## Duplicate accounting per run", "",
    "`duplicate judgements` is `stale completions - republishes - failures` (equivalently `judge invocations - results - failures`): the number of times a submission was judged beyond its first. It is NOT the stale count, which also carries one fenced original execution per reclaimed row. The residual is `invocations + republishes - (results + stale + failures)` and is 0 when every counter recorded.",
    "",
    "| run id | accepted | results | judge invocations | invocations - results | republishes | failures | stale | residual | duplicate judge ms (lower-upper) | attempts histogram |",
    "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|"
)
foreach ($run in $runs) {
    $histogram = if ($null -eq $run.attemptsHistogram) { "unavailable" } else {
        $properties = @($run.attemptsHistogram.PSObject.Properties)
        if ($properties.Count -eq 0) { "unavailable" } else { (@($properties | ForEach-Object { "$($_.Name):$($_.Value)" }) -join ', ') }
    }
    $bounds = if ($null -eq $run.duplicateJudgeMillisLowerBound) { "unavailable" } else {
        "$($run.duplicateJudgeMillisLowerBound)-$($run.duplicateJudgeMillisUpperBound)"
    }
    $lines += "| $($run.runId) | $(Format-Value $run.accepted) | $(Format-Value $run.results) | $(Format-Value $run.judgeInvocations) | " +
        "$(Format-Value $run.judgeInvocationsMinusResults) | $(Format-Value $run.storedResultRepublishes) | $(Format-Value $run.failedExecutions) | " +
        "$(Format-Value $run.staleTokenCompletions) | $(Format-Value $run.accountingResidual) | $bounds | $histogram |"
}

$lines += @(
    "", "## Throughput, backlog and latency per run", "",
    "| run id | MIF | timeout | measurement window s | accepted/s | result/s | backlog rows/s | second half rows/s | classification | growth verdict robust | p50 total ms | p95 total ms | p99 total ms | p95 result ms | p95 scoreboard ms | percentile readable as service time | 429 | 503 | 500 | drain s |",
    "|---|---:|---|---:|---:|---:|---:|---:|---|---|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|"
)
foreach ($run in $runs) {
    $lines += "| $($run.runId) | $(Format-Value $run.mysqlMaxInFlightPerNode) | $(Format-Value $run.mysqlClaimTimeout) | $(Format-Value $run.measurementWindowSeconds) | " +
        "$(Format-Value $run.acceptedPerSecond) | $(Format-Value $run.resultPerSecond) | $(Format-Value $run.backlogGrowthRowsPerSec) | $(Format-Value $run.backlogGrowthSecondHalfRowsPerSec) | " +
        "$(Format-Value $run.classification) | $(Format-Value $run.growthVerdictRobust) | $(Format-Value $run.p50TotalMs) | $(Format-Value $run.p95TotalMs) | $(Format-Value $run.p99TotalMs) | " +
        "$(Format-Value $run.p95ResultMs) | $(Format-Value $run.p95ScoreboardMs) | $(Format-Value $run.latencyReadableAsServiceTime) | " +
        "$(Format-Value $run.ko429) | $(Format-Value $run.ko503) | $(Format-Value $run.ko500) | $(Format-Value $run.drainSeconds) |"
}

$lines += @(
    "", "## Latency by deterministic class", "",
    "| run id | class | samples | L_result p50/p95/p99/max ms | L_scoreboard p50/p95/p99/max ms | L_total p50/p95/p99/max ms |",
    "|---|---|---:|---|---|---|"
)
foreach ($run in $runs) {
    foreach ($latencyClass in @("fast", "slow")) {
        $latency = if ($latencyClass -eq "fast") { $run.fastLatency } else { $run.slowLatency }
        $lines += "| $($run.runId) | $latencyClass | $(Format-Value $latency.submissionCount) | " +
            "$(Format-Value $latency.L_result_ms.p50)/$(Format-Value $latency.L_result_ms.p95)/$(Format-Value $latency.L_result_ms.p99)/$(Format-Value $latency.L_result_ms.max) | " +
            "$(Format-Value $latency.L_scoreboard_ms.p50)/$(Format-Value $latency.L_scoreboard_ms.p95)/$(Format-Value $latency.L_scoreboard_ms.p99)/$(Format-Value $latency.L_scoreboard_ms.max) | " +
            "$(Format-Value $latency.L_total_ms.p50)/$(Format-Value $latency.L_total_ms.p95)/$(Format-Value $latency.L_total_ms.p99)/$(Format-Value $latency.L_total_ms.max) |"
    }
}

$lines += @(
    "", "## Latency-class invocation and worker cost", "",
    "Run-scope accounting spans the clean post-warm-up baseline through drain. Actual milliseconds are timer measurements. Expected and duplicate milliseconds multiply the deterministic 50ms/2000ms profile and are calculations, not measured duplicate durations.", "",
    "| run id | class | unique submissions | invocations | duplicate executions | unique expected ms | actual invocation ms | profile duplicate ms |",
    "|---|---|---:|---:|---:|---:|---:|---:|"
)
foreach ($run in $runs) {
    foreach ($latencyClass in @("fast", "slow")) {
        $account = if ($latencyClass -eq "fast") { $run.latencyClassAccounting.fast } else { $run.latencyClassAccounting.slow }
        $lines += "| $($run.runId) | $latencyClass | $(Format-Value $account.uniqueSubmissions) | " +
            "$(Format-Value $account.judgeInvocations) | $(Format-Value $account.duplicateJudgeExecutions) | " +
            "$(Format-Value $account.uniqueExpectedJudgeMillis) | $(Format-Value $account.actualJudgeInvocationMillis) | " +
            "$(Format-Value $account.profileDuplicateJudgeMillis) |"
    }
    $total = $run.latencyClassAccounting.total
    $lines += "| $($run.runId) | total | $(Format-Value $total.uniqueSubmissions) | $(Format-Value $total.judgeInvocations) | " +
        "$(Format-Value $total.duplicateJudgeExecutions) | $(Format-Value $total.uniqueExpectedJudgeMillis) | " +
        "$(Format-Value $total.actualJudgeInvocationMillis) | $(Format-Value $total.profileDuplicateJudgeMillis) |"
}

$lines += @(
    "", "## Throughput and backlog endpoints", "",
    "| run id | scheduled target requests | completed window requests | accepted RPS | result RPS | scoreboard RPS | backlog start/end/peak | overall / second-half growth rows/s | drain s |",
    "|---|---:|---:|---:|---:|---:|---|---|---:|"
)
foreach ($run in $runs) {
    $lines += "| $($run.runId) | $(Format-Value $run.scheduledRequests) | $(Format-Value $run.completedWindowRequests) | " +
        "$(Format-Value $run.acceptedPerSecond) | $(Format-Value $run.resultPerSecond) | $(Format-Value $run.scoreboardAppliedPerSecond) | " +
        "$(Format-Value $run.backlogStart)/$(Format-Value $run.backlogEnd)/$(Format-Value $run.backlogPeak) | " +
        "$(Format-Value $run.backlogGrowthRowsPerSec) / $(Format-Value $run.backlogGrowthSecondHalfRowsPerSec) | $(Format-Value $run.drainSeconds) |"
}

$lines += @("", "## Per max-in-flight groups", "")
foreach ($mif in @($byMif.Keys | Sort-Object)) {
    # [object[]], not @(): PowerShell 5.1 refuses @() around the List[object] the group is built
    # in, and the failure reads only as "Argument types do not match".
    $group = [object[]]$byMif[$mif]
    $ordered = @($group | Sort-Object { Get-TimeoutMillis $_.mysqlClaimTimeout })
    $lines += "- max-in-flight ${mif}: $(($ordered | ForEach-Object { "$($_.mysqlClaimTimeout) -> duplicate claims $(Format-Value $_.duplicateClaims), duplicate judgements $(Format-Value $_.duplicateJudgeExecutions), result RPS $(Format-Value $_.resultPerSecond), p95 total $(Format-Value $_.p95TotalMs)ms, drain $(Format-Value $_.drainSeconds)s" }) -join ' | ')"
}
$lines += @("", "The timeout column is the configured claim timeout, not a measured lease duration: a claim is reclaimed by any later poll once its age passes that value, so the effective lease is the configured timeout plus up to one poll interval.")
$scopeDurations = @($runs | ForEach-Object {
    "$($_.runId)=$(Format-Value $_.latencyClassAccounting.total.measurementScopeSeconds)s"
}) -join ", "
$lines += @(
    "",
    "Scope of each column, because three different windows are in play and reading them as one is a mistake: accepted, judge invocations, the duplicate columns and the residual span the run's whole measurement phase (baseline snapshot to end snapshot) - the idle between the warm-up quiescing and the ramp, the 5s ramp, the 63s hold and the drain, not the 63s hold alone. Recorded baseline-to-end durations: $scopeDurations. The percentiles span only the 60s steady window inside that hold, with the 5s ramp excluded; the rate columns (result RPS, backlog growth, the second-half slope) span the same 60s window. The per-10k ratios divide a run-scoped count by a run-scoped denominator, so they are consistent with each other but not with the window-scoped columns beside them."
)

$lines | Set-Content $markdownPath -Encoding utf8

Write-Host "Wrote $comparisonPath and $markdownPath"
exit 0
