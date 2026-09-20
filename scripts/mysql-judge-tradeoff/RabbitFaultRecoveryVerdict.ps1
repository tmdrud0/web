<#
Verdicts for a RabbitMQ judge fail-stop run.

These are a library rather than blocks inside the runner and the analyzer for one reason: they are
the part of the run that can be decided without a stack, and a mistake in them is invisible in a
run. A run whose offered rate was 45/s and a run whose offered rate was 50/s produce the same shape
of report, and only the threshold decides which one is a capacity measurement. So the thresholds and
the conjunctions live where tests can reach them, and both the harness and the analyzer dot-source
this file rather than each carrying their own copy of a rule that would then drift.

Three rules are structural here, because every earlier mistake in this harness was one of them:

  * A missing reading is not a zero. Every function distinguishes "measured false" from "not
    measured", and a verdict that depends on an unmeasured criterion is `unavailable` rather than
    either answer. A criterion that was measured false wins over one that is unavailable, because
    one false conjunct decides the conjunction no matter what the others say.
  * The criteria and the measured values are both returned. A verdict is the conjunction's answer;
    the criteria array is what lets a reader disagree with it.
  * Every threshold used is a parameter with a default, and every returned object names the basis
    it was computed on, so the number that decided the verdict is visible in the artifact rather
    than implied by it.

No parameters block and no top-level side effects: this file is dot-sourced by the runner, by the
analyzer, and by its own tests, and it must not run anything when it is loaded.
#>

function Get-RabbitOfferedRateVerdict {
    param(
        [Parameter(Mandatory = $true)][double]$TargetRps,
        $OfferedRps,
        # The offered rate is a rate, and a rate measured over a finite window is not exact. This is
        # the band the measurement is accepted in - deliberately a percentage of the target rather
        # than an absolute count, so a run offered 400/s and a run offered 50/s are judged by the
        # same rule rather than by a rule that loosens as the target grows.
        [double]$TolerancePercent = 5
    )
    $lower = $TargetRps * (1 - ($TolerancePercent / 100))
    $upper = $TargetRps * (1 + ($TolerancePercent / 100))
    $document = [ordered]@{
        targetRps = $TargetRps
        offeredRps = $OfferedRps
        tolerancePercent = $TolerancePercent
        lowerBoundRps = [math]::Round($lower, 6)
        upperBoundRps = [math]::Round($upper, 6)
        held = $null
        verdict = "offered-rate-unavailable"
        basis = "the run's own per-second request starts, summed and divided by the measured window: the instant each request was SENT, so a client that is waiting on a slow response cannot lower its own offered rate the way a completion-log rate would"
    }
    if ($null -eq $OfferedRps) {
        # Not "short": an offer nobody measured is not an offer that fell short, and reporting it as
        # short would make every run whose request log was lost read as a load-generator failure.
        $document.basis = "the offered rate could not be measured, so this run cannot be read as having held its rate or as having fallen short of it"
        return $document
    }
    $held = ($OfferedRps -ge $lower -and $OfferedRps -le $upper)
    $document.held = $held
    if ($held) { $document.verdict = "offered-rate-held" } else { $document.verdict = "offered-rate-outside-tolerance" }
    return $document
}

function Get-RabbitConsumerTransition {
    param(
        [object[]]$Samples,
        [Parameter(Mandatory = $true)][double]$FromConsumers,
        [Parameter(Mandatory = $true)][double]$ToConsumers
    )
    <#
    The instant a queue's consumer count was first observed at the new value, having been at the old
    one before it.

    This is a bracket rather than an instant, and both ends are returned: the count is read off a
    sampler's ticks, so the change happened somewhere between the last tick at the old count and the
    first tick at the new one. Reporting only the second tick as "the instant consumers dropped"
    would put the drop after the kill by up to one tick interval, and the drop is the anchor the
    failover timings are measured from.
    #>
    $document = [ordered]@{
        observed = $false
        at = $null
        previousAt = $null
        bracketSeconds = $null
        fromConsumers = $FromConsumers
        toConsumers = $ToConsumers
        fromSampleCount = 0
        toSampleCount = 0
        unreadableSamples = 0
        basis = "the first tick observed at the destination consumer count, having previously observed the origin count; the true transition lies inside the bracket between this tick and the last tick at the origin count, and the bracket is reported rather than collapsed"
    }
    if ($null -eq $Samples -or $Samples.Count -eq 0) {
        $document.basis = "no broker samples were taken, so a consumer transition cannot be placed"
        return $document
    }
    $lastFrom = $null
    foreach ($sample in $Samples) {
        $value = $sample.consumers
        if ($null -eq $value) { $document.unreadableSamples++; continue }
        if ([double]$value -eq $FromConsumers) {
            $lastFrom = $sample
            $document.fromSampleCount++
            continue
        }
        if ([double]$value -eq $ToConsumers) {
            $document.toSampleCount++
            if ($null -ne $lastFrom -and -not $document.observed) {
                $document.observed = $true
                $document.at = $sample.at
                $document.previousAt = $lastFrom.at
                $document.bracketSeconds = [math]::Round(($sample.at - $lastFrom.at).TotalSeconds, 3)
            }
        }
    }
    return $document
}

function Get-RabbitFirstRedelivery {
    param(
        [object[]]$Samples,
        $From,
        # The broker's own cumulative redelivery counter on the live queue, read through the
        # management API. It is a counter and not an event list: RabbitMQ keeps no durable
        # per-delivery record, so this says how many redeliveries happened and never which
        # submission they were.
        [string]$Field = "redeliver"
    )
    $document = [ordered]@{
        observed = $false
        at = $null
        baseline = $null
        baselineAt = $null
        value = $null
        count = $null
        secondsAfterFrom = $null
        samplesWithoutCounter = 0
        basis = "the first tick after the given instant at which the queue's cumulative redeliver counter exceeded its value at that instant; the counter is cumulative and the count is the difference, so a redelivery that happened before the observation window cannot be read as this fault's"
    }
    if ($null -eq $From -or $null -eq $Samples -or $Samples.Count -eq 0) {
        $document.basis = "the redelivery counter could not be read against an instant, so no first redelivery can be placed"
        return $document
    }
    $ordered = @($Samples | Sort-Object -Property at)
    foreach ($sample in $ordered) {
        $value = $sample.$Field
        if ($null -eq $value) { $document.samplesWithoutCounter++; continue }
        if ($sample.at -le $From) {
            $document.baseline = $value
            $document.baselineAt = $sample.at
            continue
        }
        if ($null -eq $document.baseline) {
            # The counter was never read before the fault, so a value after it cannot be attributed
            # to this fault: the baseline is what makes the counter a delta rather than a total.
            $document.samplesWithoutCounter++
            continue
        }
        if ([double]$value -gt [double]$document.baseline) {
            $document.observed = $true
            $document.at = $sample.at
            $document.value = $value
            $document.count = [double]$value - [double]$document.baseline
            $document.secondsAfterFrom = [math]::Round(($sample.at - $From).TotalSeconds, 3)
            break
        }
    }
    return $document
}

function Get-RabbitCounterDelta {
    param(
        [object[]]$Samples,
        [Parameter(Mandatory = $true)][string]$Field,
        $From,
        $To
    )
    <#
    A cumulative broker counter's change between two instants, read from its nearest ticks at or
    before each end. The request asks for the run's broker counters as baseline-versus-end deltas,
    and this is that reading: neither end is interpolated, and an end whose nearest tick is missing
    leaves the delta unavailable rather than zero.
    #>
    $document = [ordered]@{
        available = $false
        field = $Field
        from = $null
        to = $null
        delta = $null
        fromAt = $null
        toAt = $null
        basis = "the last tick at or before each boundary; a boundary with no tick before it leaves the delta unavailable, never zero"
    }
    if ($null -eq $Samples -or $Samples.Count -eq 0) { return $document }
    $ordered = @($Samples | Sort-Object -Property at)
    foreach ($sample in $ordered) {
        if ($null -eq (Get-RabbitMemberValue -Object $sample -Name $Field)) { continue }
        if ($null -ne $From -and $sample.at -le $From) {
            $document.from = $sample.$Field
            $document.fromAt = $sample.at
        }
        if ($null -ne $To -and $sample.at -le $To) {
            $document.to = $sample.$Field
            $document.toAt = $sample.at
        }
    }
    if ($null -ne $From -and $null -eq $To) {
        # A one-sided read: the end of the series is the end of the window.
        foreach ($sample in $ordered) {
            if ($null -eq (Get-RabbitMemberValue -Object $sample -Name $Field)) { continue }
            $document.to = $sample.$Field
            $document.toAt = $sample.at
        }
    }
    if ($null -ne $document.from -and $null -ne $document.to) {
        $document.available = $true
        $document.delta = [double]$document.to - [double]$document.from
    }
    return $document
}

function Get-RabbitMemberValue {
    # A property read that reports absence as absence. Used where the caller has to tell a missing
    # counter from a counter whose value happens to be null.
    param($Object, [Parameter(Mandatory = $true)][string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-RabbitRecoveryTimes {
    param(
        $FaultAt,
        $ConsumerDroppedAt,
        $FirstRedeliveryAt,
        $ThroughputRecoveredAt,
        $BacklogNormalizedAt,
        $RestartRequestedAt,
        $NodeReadyAt,
        $ConsumerRestoredAt
    )
    <#
    Every timing this experiment reports, each measured from the anchor the request names for it and
    each null when its anchor is missing. The restart-relative readings exist because the central
    question of a fail-stop run is whether recovery waited for the replacement node: an instant that
    precedes restartRequestedAt is recovery the surviving node achieved alone, and one that follows
    nodeReadyAt is recovery that used the replacement.
    #>
    $document = [ordered]@{
        faultAt = $FaultAt
        consumerDroppedAt = $ConsumerDroppedAt
        consumerRestoredAt = $ConsumerRestoredAt
        firstRedeliveryAt = $FirstRedeliveryAt
        throughputRecoveredAt = $ThroughputRecoveredAt
        backlogNormalizedAt = $BacklogNormalizedAt
        restartRequestedAt = $RestartRequestedAt
        nodeReadyAt = $NodeReadyAt
        T_consumerDropSeconds = $null
        T_firstRedeliverySeconds = $null
        T_throughputRecoverySeconds = $null
        T_throughputRecoveryAfterConsumerDropSeconds = $null
        T_throughputRecoveryAfterRestartSeconds = $null
        T_backlogNormalizationSeconds = $null
        T_backlogNormalizationAfterRestartSeconds = $null
        T_backlogNormalizationAfterNodeReadySeconds = $null
        T_consumerRestoredAfterFaultSeconds = $null
        recoveryPrecededRestartRequest = $null
        backlogNormalizedBeforeRestart = $null
        backlogNormalizedBeforeNodeReady = $null
        firstRedeliveryBeforeRestartRequest = $null
        basis = "each T_* is the later instant minus the earlier one, both taken from this run's own observations; a null is an anchor that was never observed, and no difference is computed from a missing anchor"
    }
    if ($null -ne $FaultAt -and $null -ne $ConsumerDroppedAt) {
        $document.T_consumerDropSeconds = [math]::Round(($ConsumerDroppedAt - $FaultAt).TotalSeconds, 3)
    }
    if ($null -ne $FaultAt -and $null -ne $FirstRedeliveryAt) {
        $document.T_firstRedeliverySeconds = [math]::Round(($FirstRedeliveryAt - $FaultAt).TotalSeconds, 3)
    }
    if ($null -ne $FaultAt -and $null -ne $ThroughputRecoveredAt) {
        $document.T_throughputRecoverySeconds = [math]::Round(($ThroughputRecoveredAt - $FaultAt).TotalSeconds, 3)
    }
    if ($null -ne $ConsumerDroppedAt -and $null -ne $ThroughputRecoveredAt) {
        $document.T_throughputRecoveryAfterConsumerDropSeconds = [math]::Round(($ThroughputRecoveredAt - $ConsumerDroppedAt).TotalSeconds, 3)
    }
    if ($null -ne $RestartRequestedAt -and $null -ne $ThroughputRecoveredAt) {
        $document.T_throughputRecoveryAfterRestartSeconds = [math]::Round(($ThroughputRecoveredAt - $RestartRequestedAt).TotalSeconds, 3)
    }
    if ($null -ne $FaultAt -and $null -ne $BacklogNormalizedAt) {
        $document.T_backlogNormalizationSeconds = [math]::Round(($BacklogNormalizedAt - $FaultAt).TotalSeconds, 3)
    }
    if ($null -ne $RestartRequestedAt -and $null -ne $BacklogNormalizedAt) {
        $document.T_backlogNormalizationAfterRestartSeconds = [math]::Round(($BacklogNormalizedAt - $RestartRequestedAt).TotalSeconds, 3)
    }
    if ($null -ne $NodeReadyAt -and $null -ne $BacklogNormalizedAt) {
        $document.T_backlogNormalizationAfterNodeReadySeconds = [math]::Round(($BacklogNormalizedAt - $NodeReadyAt).TotalSeconds, 3)
    }
    if ($null -ne $FaultAt -and $null -ne $ConsumerRestoredAt) {
        $document.T_consumerRestoredAfterFaultSeconds = [math]::Round(($ConsumerRestoredAt - $FaultAt).TotalSeconds, 3)
    }
    # A strict inequality on purpose: a recovery that lands on the same tick as the restart request
    # cannot be shown to have preceded it, and the question this answers is whether it did.
    if ($null -ne $RestartRequestedAt -and $null -ne $ThroughputRecoveredAt) {
        $document.recoveryPrecededRestartRequest = ($ThroughputRecoveredAt -lt $RestartRequestedAt)
    }
    if ($null -ne $RestartRequestedAt -and $null -ne $BacklogNormalizedAt) {
        $document.backlogNormalizedBeforeRestart = ($BacklogNormalizedAt -lt $RestartRequestedAt)
    }
    if ($null -ne $NodeReadyAt -and $null -ne $BacklogNormalizedAt) {
        $document.backlogNormalizedBeforeNodeReady = ($BacklogNormalizedAt -lt $NodeReadyAt)
    }
    if ($null -ne $RestartRequestedAt -and $null -ne $FirstRedeliveryAt) {
        $document.firstRedeliveryBeforeRestartRequest = ($FirstRedeliveryAt -lt $RestartRequestedAt)
    }
    return $document
}

function Get-RabbitRedeliveryDecomposition {
    param(
        $BrokerRedeliverCount,
        $StoredResultRepublishes
    )
    <#
    What the broker's redelivery count is made of.

    Two populations are possible behind one counter, and the harness already has the metric that
    separates the larger one: `contest.judge.stored_result.republish` is incremented from exactly one
    place, the branch of the judge that finds a result already stored and republishes it instead of
    rejudging. A redelivered message whose result was already committed lands there; a message whose
    judge was killed mid-execution does not, and is rejudged.

    This is aggregate accounting and not per-submission attribution: RabbitMQ keeps no durable
    per-delivery record, so which submission was redelivered cannot be recovered from the broker.
    The residual is reported as a residual for that reason, and the report says so.
    #>
    $document = [ordered]@{
        available = $false
        brokerRedeliverCount = $BrokerRedeliverCount
        storedResultRepublishes = $StoredResultRepublishes
        republishedAfterRedelivery = $null
        rejudgedResidual = $null
        consistent = $null
        basis = "broker redeliver = stored_result.republish delta (redelivered, result already committed and republished) + residual (redelivered, rejudged because the killed JVM had not committed a result). Aggregate, not per-submission: the broker keeps no durable per-delivery record"
    }
    if ($null -eq $BrokerRedeliverCount -or $null -eq $StoredResultRepublishes) {
        $document.basis = "one of the two counters was not read, so the decomposition cannot be taken; this is reported as unavailable rather than as a residual equal to the whole count"
        return $document
    }
    $document.available = $true
    $document.republishedAfterRedelivery = $StoredResultRepublishes
    $document.rejudgedResidual = [double]$BrokerRedeliverCount - [double]$StoredResultRepublishes
    # A negative residual means the republish counter moved more than the broker recorded
    # redeliveries, which the two counters' own windows can produce: the republish counter is scraped
    # from the judge's actuator over a slightly different interval than the broker's counter. It is
    # reported rather than clamped, because clamping would hide the disagreement in the number a
    # reader would use to check the identity.
    $document.consistent = ($document.rejudgedResidual -ge 0)
    return $document
}

function Get-RabbitCriteriaVerdict {
    param(
        [Parameter(Mandatory = $true)][object[]]$Criteria,
        # The verdict when every criterion is satisfied, and the one when any is measured false.
        # The unavailable case has no name of its own because it is not an answer: it is the absence
        # of one, and it is spelled the same way whatever the run.
        [Parameter(Mandatory = $true)][string]$SatisfiedVerdict,
        [Parameter(Mandatory = $true)][string]$UnsatisfiedVerdict
    )
    <#
    One conjunction, evaluated three ways. A criterion whose `satisfied` is null was not measured;
    a criterion whose `satisfied` is false was measured and did not hold. One false conjunct decides
    the conjunction whatever the others are, so false outranks unavailable - and only when nothing
    is false does an unavailable criterion make the whole answer unavailable rather than true.
    #>
    $false0 = @()
    $unknown = @()
    foreach ($criterion in $Criteria) {
        if ($null -eq $criterion.satisfied) { $unknown += $criterion.name; continue }
        if (-not $criterion.satisfied) { $false0 += $criterion.name }
    }
    $verdict = $null
    if ($false0.Count -gt 0) { $verdict = $UnsatisfiedVerdict }
    elseif ($unknown.Count -gt 0) { $verdict = "unavailable" }
    else { $verdict = $SatisfiedVerdict }
    return [pscustomobject]@{
        verdict = $verdict
        criteria = $Criteria
        failedCriteria = $false0
        unavailableCriteria = $unknown
    }
}

function New-RabbitCriterion {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Required,
        $Measured,
        $Satisfied,
        [string]$Basis = ""
    )
    return [pscustomobject]@{
        name = $Name
        required = $Required
        measured = $Measured
        satisfied = $Satisfied
        basis = $Basis
    }
}

function Get-RabbitSingleNodeSustainableVerdict {
    param(
        # Whether the target node was observed to hold work at the moment it was killed. In rabbit
        # mode this is the broker's own per-channel unacknowledged count for that node's channels,
        # because the judge's executor gauges are registered only under mysql dispatch.
        $ActiveWorkAtKill,
        $ConsumerDropObserved,
        $OfferedRateHeld,
        $NodeDownResultRps,
        $OfferedRps,
        $NodeDownWindowSeconds,
        # The head of the down window excluded from the result-rate comparison. The first seconds
        # after a kill are partly work that was already in flight, so including them would credit the
        # surviving node with throughput the dead one had set up.
        [double]$NodeDownExcludedSeconds = 5,
        $BacklogContinuouslyGrowing,
        $BacklogNormalizedBeforeRestart,
        $IntegrityPassed,
        [double]$RequiredRatio = 0.90
    )
    <#
    The single-node-sustainable conjunction, whole. Every conjunct is a separate criterion with its
    own measured value so a verdict of "no" names which one failed, and a criterion that could not be
    measured leaves the verdict unavailable rather than false.
    #>
    $criteria = New-Object System.Collections.Generic.List[object]
    $criteria.Add((New-RabbitCriterion -Name "active-work-at-kill" -Required "the target node held unacknowledged deliveries when it was killed" `
        -Measured $ActiveWorkAtKill -Satisfied $ActiveWorkAtKill `
        -Basis "read from the broker's per-channel unacknowledged count for the killed node's channels; the judge's executor gauges are mysql-only, so the broker is the only source that can answer this under rabbit dispatch"))

    $criteria.Add((New-RabbitCriterion -Name "consumer-drop-observed" -Required "the queue's consumer count was observed to fall by one node's workers" `
        -Measured $ConsumerDropObserved -Satisfied $ConsumerDropObserved `
        -Basis "a consumer transition from the configured total to one node's share, observed on the broker's own consumer count"))

    $criteria.Add((New-RabbitCriterion -Name "offered-rate-held-during-outage" -Required "the offered rate stayed inside tolerance for the whole outage" `
        -Measured $OfferedRateHeld -Satisfied $OfferedRateHeld `
        -Basis "the offer is held through the outage on purpose: a run that lowered its rate while the node was down would measure a load the stack never had to carry"))

    $ratio = $null
    $ratioSatisfied = $null
    $ratioMeasured = $null
    if ($null -ne $NodeDownResultRps -and $null -ne $OfferedRps -and $OfferedRps -gt 0) {
        $ratio = $NodeDownResultRps / $OfferedRps
        $ratioSatisfied = ($ratio -ge $RequiredRatio)
        $ratioMeasured = [ordered]@{
            resultRps = $NodeDownResultRps
            offeredRps = $OfferedRps
            ratio = [math]::Round($ratio, 6)
            requiredRatio = $RequiredRatio
            windowSeconds = $NodeDownWindowSeconds
            excludedHeadSeconds = $NodeDownExcludedSeconds
            excludedHeadBasis = "result RPS over the down window with its first ${NodeDownExcludedSeconds}s removed, so throughput already in flight at the kill is not credited to the surviving node"
        }
    } else {
        $ratioMeasured = "result RPS or offered rate was not measured over the outage, so the comparison cannot be taken"
    }
    $criteria.Add((New-RabbitCriterion -Name "node-down-result-rps" -Required "at least ${RequiredRatio} of the offered rate is still being judged by the surviving node, excluding the first ${NodeDownExcludedSeconds}s of the outage" `
        -Measured $ratioMeasured -Satisfied $ratioSatisfied `
        -Basis "persisted results per second over the outage, measured from this run's own samples, against the offered rate the generator recorded"))

    $notGrowing = $null
    if ($null -ne $BacklogContinuouslyGrowing) { $notGrowing = (-not [bool]$BacklogContinuouslyGrowing) }
    $criteria.Add((New-RabbitCriterion -Name "backlog-not-continuously-growing" -Required "the backlog is not rising through the whole outage" `
        -Measured $BacklogContinuouslyGrowing -Satisfied $notGrowing `
        -Basis "the judge backlog is accepted submissions minus persisted results; the criterion is satisfied when that backlog is not increasing across the outage window rather than at any single instant"))

    $criteria.Add((New-RabbitCriterion -Name "backlog-normalized-before-restart" -Required "the backlog returned to its pre-fault p95 before the killed node was restarted" `
        -Measured $BacklogNormalizedBeforeRestart -Satisfied $BacklogNormalizedBeforeRestart `
        -Basis "a recovery that waited for the replacement node is not a statement about one node's capacity, so the normalisation has to precede restartRequestedAt to count here"))

    $criteria.Add((New-RabbitCriterion -Name "no-loss-or-integrity-error" -Required "every accepted submission has exactly one result and one scoreboard application" `
        -Measured $IntegrityPassed -Satisfied $IntegrityPassed `
        -Basis "accepted = unique submissions = results = scoreboard applied, with no dead-lettered message and no duplicate result row"))

    $criteriaArray = $criteria.ToArray()
    $evaluated = Get-RabbitCriteriaVerdict -Criteria $criteriaArray -SatisfiedVerdict "single-node-sustainable" -UnsatisfiedVerdict "not-single-node-sustainable"
    return [pscustomobject]@{
        verdict = $evaluated.verdict
        criteria = $criteriaArray
        failedCriteria = $evaluated.failedCriteria
        unavailableCriteria = $evaluated.unavailableCriteria
        measured = [ordered]@{
            activeWorkAtKill = $ActiveWorkAtKill
            consumerDropObserved = $ConsumerDropObserved
            offeredRateHeld = $OfferedRateHeld
            nodeDownResultRps = $NodeDownResultRps
            offeredRps = $OfferedRps
            resultToOfferedRatio = $ratio
            backlogContinuouslyGrowing = $BacklogContinuouslyGrowing
            backlogNormalizedBeforeRestart = $BacklogNormalizedBeforeRestart
            integrityPassed = $IntegrityPassed
        }
        basis = "single-node-sustainable requires every criterion above to hold in one run: active work present at the kill, the consumer count observed to fall, the offered rate held through the outage, at least ${RequiredRatio} of the offered rate still judged by the surviving node after the first ${NodeDownExcludedSeconds}s, no continuous backlog growth, no loss and no integrity error"
    }
}

function Get-RabbitFastFailoverVerdict {
    param(
        $ThroughputRecoveredAt,
        $FaultAt,
        $ConsumerDroppedAt,
        $FirstRedeliveryAt,
        $RestartRequestedAt,
        # "Right after" needs a number or it is not a criterion. The same bound is used for both
        # clocks here because the request gives one bound for both, and it is a parameter so the
        # report states the number that decided the verdict instead of implying one.
        [double]$WithinSeconds = 5
    )
    <#
    The fast-failover conjunction.

    The request states the throughput criterion as recovering within five seconds of the fault or of
    the consumer drop. That is one criterion with two clocks rather than two criteria: a queue whose
    consumers fall a second after the kill can pass one and fail the other for the same recovery.
    Both readings are reported, and the criterion records which clock satisfied it.
    #>
    $criteria = New-Object System.Collections.Generic.List[object]
    $faultRelative = $null
    $dropRelative = $null
    if ($null -ne $ThroughputRecoveredAt -and $null -ne $FaultAt) {
        $faultRelative = [math]::Round(($ThroughputRecoveredAt - $FaultAt).TotalSeconds, 3)
    }
    if ($null -ne $ThroughputRecoveredAt -and $null -ne $ConsumerDroppedAt) {
        $dropRelative = [math]::Round(($ThroughputRecoveredAt - $ConsumerDroppedAt).TotalSeconds, 3)
    }
    $throughputSatisfied = $null
    if ($null -ne $faultRelative -or $null -ne $dropRelative) {
        $throughputSatisfied = $false
        if ($null -ne $faultRelative -and $faultRelative -le $WithinSeconds) { $throughputSatisfied = $true }
        if ($null -ne $dropRelative -and $dropRelative -le $WithinSeconds) { $throughputSatisfied = $true }
    }
    $criteria.Add((New-RabbitCriterion -Name "throughput-recovered-within-window" -Required "result throughput back to its pre-fault level within ${WithinSeconds}s of the fault or of the consumer drop" `
        -Measured ([ordered]@{ secondsAfterFault = $faultRelative; secondsAfterConsumerDrop = $dropRelative; withinSeconds = $WithinSeconds }) `
        -Satisfied $throughputSatisfied `
        -Basis "one criterion with two clocks, because the request states it that way: a recovery that satisfies either relative reading satisfies this, and both readings are reported so which one carried it is visible"))

    $redeliveryRelative = $null
    if ($null -ne $FirstRedeliveryAt -and $null -ne $FaultAt) {
        $redeliveryRelative = [math]::Round(($FirstRedeliveryAt - $FaultAt).TotalSeconds, 3)
    }
    $redeliverySatisfied = $null
    if ($null -ne $redeliveryRelative) { $redeliverySatisfied = ($redeliveryRelative -le $WithinSeconds) }
    $criteria.Add((New-RabbitCriterion -Name "first-redelivery-right-after-fault" -Required "the broker redelivered the dead node's unacknowledged work within ${WithinSeconds}s of the fault" `
        -Measured ([ordered]@{ secondsAfterFault = $redeliveryRelative; withinSeconds = $WithinSeconds }) `
        -Satisfied $redeliverySatisfied `
        -Basis "the queue's cumulative redeliver counter rising above its pre-fault value; a redelivery is the broker's response to the lost connection, so it is the earliest evidence the pipeline noticed at all"))

    $restartSatisfied = $null
    if ($null -ne $ThroughputRecoveredAt -and $null -ne $RestartRequestedAt) {
        $restartSatisfied = ($ThroughputRecoveredAt -lt $RestartRequestedAt)
    }
    $criteria.Add((New-RabbitCriterion -Name "recovery-began-without-restart" -Required "throughput recovered before the killed node was restarted" `
        -Measured ([ordered]@{ throughputRecoveredAt = $ThroughputRecoveredAt; restartRequestedAt = $RestartRequestedAt }) `
        -Satisfied $restartSatisfied `
        -Basis "a recovery that only arrives with the replacement node is not failover: it is the cluster returning to its previous size"))

    $criteriaArray = $criteria.ToArray()
    $evaluated = Get-RabbitCriteriaVerdict -Criteria $criteriaArray -SatisfiedVerdict "fast-failover" -UnsatisfiedVerdict "not-fast-failover"
    return [pscustomobject]@{
        verdict = $evaluated.verdict
        criteria = $criteriaArray
        failedCriteria = $evaluated.failedCriteria
        unavailableCriteria = $evaluated.unavailableCriteria
        measured = [ordered]@{
            throughputRecoveredAt = $ThroughputRecoveredAt
            secondsAfterFault = $faultRelative
            secondsAfterConsumerDrop = $dropRelative
            firstRedeliveryAt = $FirstRedeliveryAt
            firstRedeliverySecondsAfterFault = $redeliveryRelative
            restartRequestedAt = $RestartRequestedAt
            withinSeconds = $WithinSeconds
        }
        basis = "fast-failover requires the throughput recovery inside ${WithinSeconds}s of the fault or of the consumer drop, the first redelivery inside the same window, and the recovery to have begun before the killed node was restarted"
    }
}

function Get-RabbitBacklogGrowthVerdict {
    param(
        [object[]]$Samples,
        $From,
        $To,
        [string]$Field = "judgeBacklog",
        # How much of the sampled window must actually be covered before growth is called
        # continuous. A verdict read off two samples is not a statement about a window.
        [int]$MinimumSamples = 4
    )
    <#
    Whether the backlog rose through the whole window, which is the fifth criterion of
    single-node-sustainable and is not the same question as whether it peaked. A backlog that rises
    and then falls is a saturated-but-recovering pipeline; one that only rises is a pipeline losing
    ground. Reported with both the endpoints and the peak so a reader can see which shape the
    series had rather than only the yes or no.
    #>
    $document = [ordered]@{
        verdict = "unavailable"
        continuouslyGrowing = $null
        from = $null
        to = $null
        firstValue = $null
        lastValue = $null
        peakValue = $null
        peakAt = $null
        sampleCount = 0
        riseRatePerSecond = $null
        basis = "every sampled backlog value in the window is non-decreasing and the last exceeds the first; a window with too few readable samples is reported unavailable rather than as growth"
    }
    if ($null -eq $From -or $null -eq $To -or $null -eq $Samples) { return $document }
    $window = @($Samples | Where-Object { $_.at -ge $From -and $_.at -le $To -and $null -ne (Get-RabbitMemberValue -Object $_ -Name $Field) } |
        Sort-Object -Property at)
    $document.sampleCount = $window.Count
    if ($window.Count -lt $MinimumSamples) {
        $document.basis = "only $($window.Count) readable backlog samples fell in the window, below the $MinimumSamples this needs before it will call a trend continuous"
        return $document
    }
    $first = $window[0]
    $last = $window[-1]
    $document.from = $first.at
    $document.to = $last.at
    $document.firstValue = $first.$Field
    $document.lastValue = $last.$Field
    $peak = $window[0]
    $monotonic = $true
    for ($i = 1; $i -lt $window.Count; $i++) {
        if ([double]$window[$i].$Field -lt [double]$window[$i - 1].$Field) { $monotonic = $false }
        if ([double]$window[$i].$Field -gt [double]$peak.$Field) { $peak = $window[$i] }
    }
    $document.peakValue = $peak.$Field
    $document.peakAt = $peak.at
    $span = ($last.at - $first.at).TotalSeconds
    if ($span -gt 0) {
        $document.riseRatePerSecond = [math]::Round(([double]$last.$Field - [double]$first.$Field) / $span, 6)
    }
    $growing = ($monotonic -and [double]$last.$Field -gt [double]$first.$Field)
    $document.continuouslyGrowing = $growing
    if ($growing) { $document.verdict = "continuously-growing" } else { $document.verdict = "not-continuously-growing" }
    return $document
}

function Get-RabbitBacklogPeak {
    param(
        [object[]]$Samples,
        $From,
        $To,
        [string]$Field = "judgeBacklog"
    )
    <#
    The peak and its instant, which the request asks for independently of the normalisation. A peak
    is an observed maximum, so it is always reported with the instant it was observed at: a peak
    without its time cannot be told apart from a value the series merely ended on.
    #>
    $document = [ordered]@{
        available = $false
        value = $null
        at = $null
        sampleCount = 0
        basis = "the largest backlog value observed in the window, with the instant it was observed at"
    }
    if ($null -eq $Samples) { return $document }
    $window = @($Samples | Where-Object {
        ($null -eq $From -or $_.at -ge $From) -and ($null -eq $To -or $_.at -le $To) -and
        $null -ne (Get-RabbitMemberValue -Object $_ -Name $Field) })
    $document.sampleCount = $window.Count
    if ($window.Count -eq 0) { return $document }
    $peak = $window[0]
    foreach ($sample in $window) { if ([double]$sample.$Field -gt [double]$peak.$Field) { $peak = $sample } }
    $document.available = $true
    $document.value = $peak.$Field
    $document.at = $peak.at
    return $document
}

# --- readers -------------------------------------------------------------------------------------
# The four functions below turn a run's own CSV artifacts into typed series. They are here rather
# than in the analyzer for the same reason the verdicts are: a CSV round trip is where a missing
# reading becomes a zero. An empty cell means the counter was not read, and it has to arrive at a
# verdict as "not measured" rather than as "measured 0", so the coercion is one function both the
# analyzer and the tests go through.

function Get-RabbitNumberOrNull {
    <#
    A numeric cell, or null. An empty or whitespace cell is null, and so is a cell that does not
    parse - including one written in a culture whose decimal separator is a comma, which is why the
    parse is invariant rather than culture-sensitive.
    #>
    param($Value)
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = 0.0
    if (-not [double]::TryParse($text, [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) { return $null }
    return $parsed
}

function Get-RabbitInstantOrNull {
    # A round-trip timestamp, or null. The sampler writes `o` with an offset, so a value that does not
    # carry one is still parsed rather than dropped: an offset-less instant is read as local, which is
    # a worse reading than a correct one but a better one than a row silently leaving the series.
    param($Value)
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    $parsed = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse($text, [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind, [ref]$parsed)) { return $null }
    return $parsed
}

function Get-RabbitQueueSampleSeries {
    param([object[]]$Rows)
    <#
    The broker sampler's own CSV as a typed, time-ordered series.

    It is sorted here rather than by each caller: the consumer transition and the first redelivery are
    both "the first sample that ..." questions, and an unsorted series answers them with whichever row
    happened to come first in the file.
    #>
    $document = [ordered]@{
        samples = @()
        rowCount = 0
        unreadableTimestampRows = 0
        firstAt = $null
        lastAt = $null
        basis = "rabbit-queue-samples.csv, one row per broker tick; every counter is null when its cell is empty, because a counter the sampler could not read is not a counter that read zero"
    }
    if ($null -eq $Rows) { return $document }
    $samples = New-Object System.Collections.Generic.List[object]
    foreach ($row in @($Rows)) {
        $document.rowCount++
        $at = Get-RabbitInstantOrNull (Get-RabbitMemberValue -Object $row -Name "timestamp")
        if ($null -eq $at) { $document.unreadableTimestampRows++; continue }
        $samples.Add([pscustomobject]@{
            at = $at
            phase = Get-RabbitMemberValue -Object $row -Name "phase"
            ready = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "ready")
            unacked = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "unacked")
            readyPlusUnacked = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "readyPlusUnacked")
            consumers = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "consumers")
            connections = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "connections")
            channels = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "channels")
            publish = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "publish")
            deliver = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "deliver")
            ack = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "ack")
            redeliver = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "redeliver")
            deadReady = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "deadReady")
            deadUnacked = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "deadUnacked")
            deadLetters = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "deadLetters")
            deadConsumers = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "deadConsumers")
        })
    }
    $sorted = @($samples | Sort-Object -Property at)
    $document.samples = $sorted
    if ($sorted.Count -gt 0) {
        $document.firstAt = $sorted[0].at
        $document.lastAt = $sorted[-1].at
    }
    return $document
}

function Get-RabbitResultSampleSeries {
    param([object[]]$Rows)
    <#
    recovery-samples.csv as a typed series, with the same rule: an empty cell is a reading that was
    not taken.
    #>
    $document = [ordered]@{
        samples = @()
        rowCount = 0
        unreadableTimestampRows = 0
        firstAt = $null
        lastAt = $null
        basis = "recovery-samples.csv, one row per measured-phase tick; judgeBacklog is the run's own backlog column, which for rabbit dispatch is accepted submissions minus persisted results"
    }
    if ($null -eq $Rows) { return $document }
    $samples = New-Object System.Collections.Generic.List[object]
    foreach ($row in @($Rows)) {
        $document.rowCount++
        $at = Get-RabbitInstantOrNull (Get-RabbitMemberValue -Object $row -Name "at")
        if ($null -eq $at) { $document.unreadableTimestampRows++; continue }
        $samples.Add([pscustomobject]@{
            at = $at
            judgeBacklog = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "judgeBacklog")
            scoreboardPending = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "scoreboardPending")
            accepted = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "accepted")
            results = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "results")
        })
    }
    $sorted = @($samples | Sort-Object -Property at)
    $document.samples = $sorted
    if ($sorted.Count -gt 0) {
        $document.firstAt = $sorted[0].at
        $document.lastAt = $sorted[-1].at
    }
    return $document
}

function Get-RabbitOfferedRateFromBuckets {
    param(
        [object[]]$Rows,
        # The generator's own name for the submit request. A total across every request name would
        # count the logins as offered submissions.
        [Parameter(Mandatory = $true)][string]$RequestName,
        $From,
        $To,
        # A rate read off two seconds is not a rate. Fewer whole seconds than this and the reading is
        # reported as unavailable rather than as a number that would then decide a verdict.
        [int]$MinimumSeconds = 5
    )
    <#
    The offered rate, from the generator's per-second buckets rather than from a target.

    The rows are bucketed by the instant each request was SENT, so this is an offer and not a
    completion count: a client waiting on a slow response cannot lower its own offered rate here. The
    denominator is the whole seconds the window spans, including seconds in which nothing was logged,
    which is what makes an interrupted offer read as a lower rate instead of as a shorter one.
    #>
    $document = [ordered]@{
        available = $false
        requestName = $RequestName
        offered = $null
        seconds = $null
        rps = $null
        firstSecondUtc = $null
        lastSecondUtc = $null
        bucketCount = 0
        from = $From
        to = $To
        basis = "the generator's own per-second request rows for one request name, summed over the whole seconds the window spans and divided by that span; a second that logged nothing is part of the denominator, so a gap lowers the rate rather than shortening the window"
    }
    if ($null -eq $Rows) {
        $document.basis = "the per-second request rows were not read, so no offered rate exists for this window"
        return $document
    }
    $fromSecond = $null
    $toSecond = $null
    if ($null -ne $From) { $fromSecond = [long][math]::Floor(([datetimeoffset]$From).ToUnixTimeSeconds()) }
    if ($null -ne $To) { $toSecond = [long][math]::Floor(([datetimeoffset]$To).ToUnixTimeSeconds()) }
    $seconds = New-Object System.Collections.Generic.List[long]
    $offered = 0.0
    foreach ($row in @($Rows)) {
        if ([string](Get-RabbitMemberValue -Object $row -Name "request") -ne $RequestName) { continue }
        $second = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "epochSecond")
        if ($null -eq $second) { continue }
        $second = [long]$second
        if ($null -ne $fromSecond -and $second -lt $fromSecond) { continue }
        if ($null -ne $toSecond -and $second -gt $toSecond) { continue }
        $value = Get-RabbitNumberOrNull (Get-RabbitMemberValue -Object $row -Name "offered")
        if ($null -eq $value) { continue }
        $offered += $value
        $document.bucketCount++
        if (-not $seconds.Contains($second)) { $seconds.Add($second) }
    }
    if ($seconds.Count -eq 0) {
        $document.basis = "no per-second bucket for $RequestName fell inside the window, so the offered rate is unavailable rather than zero"
        return $document
    }
    $sortedSeconds = @($seconds | Sort-Object)
    $span = ($sortedSeconds[-1] - $sortedSeconds[0]) + 1
    $document.offered = $offered
    $document.seconds = $span
    $document.firstSecondUtc = [datetimeoffset]::FromUnixTimeSeconds($sortedSeconds[0]).ToString("o")
    $document.lastSecondUtc = [datetimeoffset]::FromUnixTimeSeconds($sortedSeconds[-1]).ToString("o")
    if ($span -lt $MinimumSeconds) {
        $document.basis = "the window covers only $span whole seconds of buckets, below the $MinimumSeconds a rate needs before it is reported"
        return $document
    }
    $document.available = $true
    $document.rps = [math]::Round($offered / $span, 6)
    return $document
}

function Get-RabbitWindowResultRps {
    param(
        [object[]]$Samples,
        $From,
        $To,
        [double]$ExcludeHeadSeconds = 0,
        [int]$MinimumSamples = 2
    )
    <#
    Persisted results per second over a window, from the cumulative results column.

    Two cumulative readings and the time between them, rather than a count of events: this run's
    sample series carries counts, not per-result instants, and a difference of two counts over a known
    span is the same rate without needing the individual events.

    The head of the window is excluded because the first seconds after a kill are partly work the dead
    node had already set up, and crediting the surviving node with it is how a fail-stop run reports a
    throughput it never sustained.
    #>
    $document = [ordered]@{
        available = $false
        from = $From
        to = $To
        excludeHeadSeconds = $ExcludeHeadSeconds
        windowSeconds = $null
        sampleCount = 0
        firstValue = $null
        lastValue = $null
        firstAt = $null
        lastAt = $null
        resultRps = $null
        basis = "the cumulative results column over the window with its first ${ExcludeHeadSeconds}s removed: the last readable reading minus the first, divided by the seconds between them. Two readings are the floor, and fewer leaves this unavailable rather than zero"
    }
    if ($null -eq $From -or $null -eq $Samples -or $Samples.Count -eq 0) {
        $document.basis = "the window or the sample series is missing, so no result rate can be taken over it"
        return $document
    }
    $windowStart = ([datetimeoffset]$From).AddSeconds($ExcludeHeadSeconds)
    $window = @($Samples | Where-Object {
        $null -ne (Get-RabbitMemberValue -Object $_ -Name "results") -and
        $_.at -ge $windowStart -and ($null -eq $To -or $_.at -le $To) } |
        Sort-Object -Property at)
    $document.sampleCount = $window.Count
    if ($window.Count -lt $MinimumSamples) {
        $document.basis = "only $($window.Count) readable result readings fell in the window, below the $MinimumSamples this needs before it will divide by a span"
        return $document
    }
    $first = $window[0]
    $last = $window[-1]
    $span = ($last.at - $first.at).TotalSeconds
    $document.firstValue = $first.results
    $document.lastValue = $last.results
    $document.firstAt = $first.at
    $document.lastAt = $last.at
    $document.windowSeconds = [math]::Round($span, 3)
    if ($span -le 0) {
        $document.basis = "the readable readings in the window share one instant, so there is no span to divide by"
        return $document
    }
    $document.available = $true
    $document.resultRps = [math]::Round(([double]$last.results - [double]$first.results) / $span, 6)
    return $document
}
