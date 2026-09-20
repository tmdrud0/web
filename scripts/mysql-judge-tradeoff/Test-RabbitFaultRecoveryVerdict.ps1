[CmdletBinding()]
<#
Checks for the rabbit fault-recovery verdicts.

These are the parts of a fail-stop run that can be decided without a stack, and they are the parts a
mistake in would be invisible in a run: a verdict that reads an unmeasured criterion as false, a
recovery time measured from the wrong anchor, a redelivery count read as the whole redelivery
population, or a backlog trend called continuous off two samples. Everything else about the run is
a measurement, and a measurement is judged by its own evidence.

Run directly: powershell -ExecutionPolicy Bypass -File Test-RabbitFaultRecoveryVerdict.ps1
Exits 0 when every check passes, 1 otherwise, naming each failure.
#>
param()

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "RabbitFaultRecoveryVerdict.ps1")

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]

function Assert-Equal {
    param($Expected, $Actual, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if ($Expected -ne $Actual) {
        $script:failures.Add("$Because`: expected [$Expected], got [$Actual]")
    }
}

function Assert-True {
    param($Value, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if (-not $Value) {
        $script:failures.Add("$Because`: expected true, got [$Value]")
    }
}

function Assert-False {
    param($Value, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if ($Value) {
        $script:failures.Add("$Because`: expected false, got [$Value]")
    }
}

function Assert-Null {
    param($Value, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if ($null -ne $Value) {
        $script:failures.Add("$Because`: expected null, got [$Value]")
    }
}

function Assert-Contains {
    param([string]$Text, [string]$Fragment, [Parameter(Mandatory = $true)][string]$Because)
    $script:checks++
    if ($Text -notlike "*$Fragment*") {
        $script:failures.Add("$Because`: [$Text] does not contain [$Fragment]")
    }
}

function New-BrokerSample {
    <#
    A broker sample as the sampler writes it. Every field is overridable so a case can change exactly
    the one thing it is about, and `$Omit` removes a field entirely - which is a different case from
    setting it to zero, and the one this library has to get right.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$At,
        $Consumers = 32,
        $Redeliver = $null,
        $JudgeBacklog = $null,
        $Ready = $null,
        [string[]]$Omit = @()
    )
    $sample = [pscustomobject]@{
        at = [datetimeoffset]::Parse($At)
        consumers = $Consumers
        redeliver = $Redeliver
        judgeBacklog = $JudgeBacklog
        ready = $Ready
    }
    foreach ($name in $Omit) { $sample.PSObject.Properties.Remove($name) | Out-Null }
    return $sample
}

# --- the offered rate -----------------------------------------------------------------------------
$held = Get-RabbitOfferedRateVerdict -TargetRps 50 -OfferedRps 50.2
Assert-Equal "offered-rate-held" $held.verdict "an offer of 50.2/s against a 50/s target is inside tolerance"
Assert-True $held.held "a held offer says so"
Assert-Equal 47.5 $held.lowerBoundRps "the lower bound is 5% below the target"
Assert-Equal 52.5 $held.upperBoundRps "the upper bound is 5% above the target"

$lowEdge = Get-RabbitOfferedRateVerdict -TargetRps 50 -OfferedRps 47.5
Assert-Equal "offered-rate-held" $lowEdge.verdict "the lower bound is inside the band, not outside it"

$justUnder = Get-RabbitOfferedRateVerdict -TargetRps 50 -OfferedRps 47.4
Assert-Equal "offered-rate-outside-tolerance" $justUnder.verdict "a rate below the lower bound is outside tolerance"

$short = Get-RabbitOfferedRateVerdict -TargetRps 50 -OfferedRps 44.0
Assert-Equal "offered-rate-outside-tolerance" $short.verdict "44/s against a 50/s target is not the rate this run names"

# An offer nobody measured is not an offer that fell short. Reading it as short would make every run
# whose request log was lost look like a load-generator failure, which is the opposite conclusion.
$unmeasured = Get-RabbitOfferedRateVerdict -TargetRps 50 -OfferedRps $null
Assert-Equal "offered-rate-unavailable" $unmeasured.verdict "an unmeasured offer is unavailable, not outside tolerance"
Assert-Null $unmeasured.held "an unmeasured offer has no held reading"
Assert-Contains $unmeasured.basis "could not be measured" "the unavailable offer says why"

# --- the consumer transition ----------------------------------------------------------------------
$transition = @(
    New-BrokerSample -At "2026-09-20T12:00:00.000Z" -Consumers 32
    New-BrokerSample -At "2026-09-20T12:00:00.250Z" -Consumers 32
    New-BrokerSample -At "2026-09-20T12:00:00.500Z" -Consumers 16
    New-BrokerSample -At "2026-09-20T12:00:00.750Z" -Consumers 16
)
$drop = Get-RabbitConsumerTransition -Samples $transition -FromConsumers 32 -ToConsumers 16
Assert-True $drop.observed "a 32 to 16 fall is observed"
Assert-Equal ([datetimeoffset]::Parse("2026-09-20T12:00:00.500Z")) $drop.at "the transition is placed at the first tick at the new count"
Assert-Equal ([datetimeoffset]::Parse("2026-09-20T12:00:00.250Z")) $drop.previousAt "the bracket's other end is the last tick at the old count"
Assert-Equal 0.25 $drop.bracketSeconds "the bracket is reported rather than collapsed"
Assert-Equal 2 $drop.fromSampleCount "the ticks at the origin count are counted"
Assert-Equal 2 $drop.toSampleCount "the ticks at the destination count are counted"

# The count rising is the same question the other way, and it is asked of the same function because
# a second implementation would be a second place for the bracket to be collapsed.
$restore = Get-RabbitConsumerTransition -Samples $transition -FromConsumers 16 -ToConsumers 32
Assert-False $restore.observed "a series that only falls never shows the rise, whatever it ends at"

$nullsBetween = @(
    New-BrokerSample -At "2026-09-20T12:00:00.000Z" -Consumers 32
    New-BrokerSample -At "2026-09-20T12:00:00.250Z" -Consumers $null
    New-BrokerSample -At "2026-09-20T12:00:00.500Z" -Consumers 16
)
$throughNulls = Get-RabbitConsumerTransition -Samples $nullsBetween -FromConsumers 32 -ToConsumers 16
Assert-True $throughNulls.observed "an unreadable tick does not erase the origin count that was seen before it"
Assert-Equal 1 $throughNulls.unreadableSamples "the unreadable tick is counted and reported"

$noSamples = Get-RabbitConsumerTransition -Samples @() -FromConsumers 32 -ToConsumers 16
Assert-False $noSamples.observed "a transition cannot be placed without samples"
Assert-Contains $noSamples.basis "no broker samples" "and the reason is recorded"

# --- the first redelivery --------------------------------------------------------------------------
$faultAt = [datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")
$redeliverySeries = @(
    New-BrokerSample -At "2026-09-20T12:00:09.000Z" -Redeliver 0
    New-BrokerSample -At "2026-09-20T12:00:10.000Z" -Redeliver 0
    New-BrokerSample -At "2026-09-20T12:00:10.250Z" -Redeliver 4
    New-BrokerSample -At "2026-09-20T12:00:10.500Z" -Redeliver 11
)
$firstRedelivery = Get-RabbitFirstRedelivery -Samples $redeliverySeries -From $faultAt
Assert-True $firstRedelivery.observed "a cumulative redeliver counter rising above its pre-fault value places a first redelivery"
Assert-Equal ([datetimeoffset]::Parse("2026-09-20T12:00:10.250Z")) $firstRedelivery.at "the first redelivery is the first tick that exceeded the baseline"
Assert-Equal 4 $firstRedelivery.count "the count is the difference from the baseline, not the cumulative total"
Assert-Equal 0.25 $firstRedelivery.secondsAfterFrom "the offset from the fault is reported"

# A counter with no pre-fault reading cannot have its post-fault value attributed to this fault.
$noBaseline = @(
    New-BrokerSample -At "2026-09-20T12:00:10.250Z" -Redeliver 4
    New-BrokerSample -At "2026-09-20T12:00:10.500Z" -Redeliver 11
)
$unbased = Get-RabbitFirstRedelivery -Samples $noBaseline -From $faultAt
Assert-False $unbased.observed "a redeliver counter first read after the fault has no baseline to be a delta of"
Assert-Null $unbased.baseline "the missing baseline is null rather than zero"
Assert-True ($unbased.samplesWithoutCounter -ge 1) "the samples that could not be used are counted"

# A field the payload omitted is missing evidence, not a zero: the counter's absence must not be
# read as "no redeliveries happened".
$omitted = @(
    New-BrokerSample -At "2026-09-20T12:00:09.000Z" -Omit @("redeliver")
    New-BrokerSample -At "2026-09-20T12:00:10.250Z" -Omit @("redeliver")
)
$omittedResult = Get-RabbitFirstRedelivery -Samples $omitted -From $faultAt
Assert-False $omittedResult.observed "an omitted redeliver field is not read as a redelivery of zero"
Assert-Equal 2 $omittedResult.samplesWithoutCounter "both unreadable ticks are counted"

# A counter that never moves places no redelivery, however many ticks are taken.
$flat = @(
    New-BrokerSample -At "2026-09-20T12:00:09.000Z" -Redeliver 0
    New-BrokerSample -At "2026-09-20T12:00:12.000Z" -Redeliver 0
)
$flatResult = Get-RabbitFirstRedelivery -Samples $flat -From $faultAt
Assert-False $flatResult.observed "a redeliver counter that never rises places no redelivery"
Assert-Equal 0 $flatResult.baseline "the baseline is read even when nothing rises above it"

# --- counter deltas -------------------------------------------------------------------------------
$deltaSeries = @(
    New-BrokerSample -At "2026-09-20T12:00:00.000Z" -Redeliver 3
    New-BrokerSample -At "2026-09-20T12:00:10.000Z" -Redeliver 3
    New-BrokerSample -At "2026-09-20T12:00:30.000Z" -Redeliver 40
)
$delta = Get-RabbitCounterDelta -Samples $deltaSeries -Field "redeliver" -From $faultAt -To ([datetimeoffset]::Parse("2026-09-20T12:00:40.000Z"))
Assert-True $delta.available "a counter read at both boundaries has a delta"
Assert-Equal 37 $delta.delta "the delta is end minus baseline"
Assert-Equal ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")) $delta.fromAt "the baseline end is the last tick at or before the boundary"

$noEnd = Get-RabbitCounterDelta -Samples @(New-BrokerSample -At "2026-09-20T12:00:10.000Z" -Redeliver 3) -Field "redeliver" -From $faultAt -To $faultAt
Assert-True $noEnd.available "a bounded window whose end has a tick at or before it is readable"

$noStart = Get-RabbitCounterDelta -Samples @(New-BrokerSample -At "2026-09-20T12:00:20.000Z" -Redeliver 9) -Field "redeliver" `
    -From ([datetimeoffset]::Parse("2026-09-20T12:00:00.000Z")) -To ([datetimeoffset]::Parse("2026-09-20T12:00:21.000Z"))
Assert-False $noStart.available "a boundary with no tick before it leaves the delta unavailable"
Assert-Null $noStart.delta "and null rather than zero"

# --- recovery times --------------------------------------------------------------------------------
$consumerDroppedAt = [datetimeoffset]::Parse("2026-09-20T12:00:10.400Z")
$firstRedeliveryAt = [datetimeoffset]::Parse("2026-09-20T12:00:10.250Z")
$throughputRecoveredAt = [datetimeoffset]::Parse("2026-09-20T12:00:13.000Z")
$backlogNormalizedAt = [datetimeoffset]::Parse("2026-09-20T12:00:19.000Z")
$restartRequestedAt = [datetimeoffset]::Parse("2026-09-20T12:00:25.000Z")
$nodeReadyAt = [datetimeoffset]::Parse("2026-09-20T12:00:48.000Z")
$times = Get-RabbitRecoveryTimes -FaultAt $faultAt -ConsumerDroppedAt $consumerDroppedAt `
    -FirstRedeliveryAt $firstRedeliveryAt -ThroughputRecoveredAt $throughputRecoveredAt `
    -BacklogNormalizedAt $backlogNormalizedAt -RestartRequestedAt $restartRequestedAt -NodeReadyAt $nodeReadyAt
Assert-Equal 0.4 $times.T_consumerDropSeconds "the consumer drop is measured from the fault"
Assert-Equal 0.25 $times.T_firstRedeliverySeconds "the first redelivery is measured from the fault"
Assert-Equal 3.0 $times.T_throughputRecoverySeconds "the throughput recovery is measured from the fault"
Assert-Equal 2.6 $times.T_throughputRecoveryAfterConsumerDropSeconds "and from the consumer drop"
Assert-Equal (-12.0) $times.T_throughputRecoveryAfterRestartSeconds "a recovery before the restart is negative against it, which is the finding"
Assert-Equal 9.0 $times.T_backlogNormalizationSeconds "the normalisation is measured from the fault"
Assert-Equal (-6.0) $times.T_backlogNormalizationAfterRestartSeconds "and is negative against a restart it preceded"
Assert-Equal (-29.0) $times.T_backlogNormalizationAfterNodeReadySeconds "and against readiness"
Assert-True $times.recoveryPrecededRestartRequest "throughput recovery before the restart is reported as having preceded it"
Assert-True $times.backlogNormalizedBeforeRestart "and the normalisation is the criterion the verdict reads"
Assert-True $times.backlogNormalizedBeforeNodeReady "the normalisation also preceded readiness"
Assert-True $times.firstRedeliveryBeforeRestartRequest "the redelivery preceded the restart too"

# Recovery landing exactly on the restart request is not evidence that it preceded it.
$onTheTick = Get-RabbitRecoveryTimes -FaultAt $faultAt -ThroughputRecoveredAt $restartRequestedAt -RestartRequestedAt $restartRequestedAt
Assert-False $onTheTick.recoveryPrecededRestartRequest "a recovery on the restart tick did not precede it"

$noAnchors = Get-RabbitRecoveryTimes -FaultAt $faultAt -ConsumerDroppedAt $null -FirstRedeliveryAt $null `
    -ThroughputRecoveredAt $null -BacklogNormalizedAt $null -RestartRequestedAt $restartRequestedAt -NodeReadyAt $null
Assert-Null $noAnchors.T_consumerDropSeconds "a missing anchor makes its timing unavailable"
Assert-Null $noAnchors.T_firstRedeliverySeconds "and so does a missing redelivery"
Assert-Null $noAnchors.T_backlogNormalizationSeconds "and a missing normalisation"
Assert-Null $noAnchors.backlogNormalizedBeforeRestart "a normalisation that was never observed is not 'before the restart'"
Assert-Null $noAnchors.recoveryPrecededRestartRequest "and neither is a throughput recovery that was never observed"

# --- the redelivery decomposition ------------------------------------------------------------------
$decomposition = Get-RabbitRedeliveryDecomposition -BrokerRedeliverCount 20 -StoredResultRepublishes 15
Assert-True $decomposition.available "two counters give a decomposition"
Assert-Equal 15 $decomposition.republishedAfterRedelivery "the republish counter is the population whose result was already committed"
Assert-Equal 5 $decomposition.rejudgedResidual "the residual is what was redelivered and rejudged"
Assert-True $decomposition.consistent "a non-negative residual is consistent with the broker's count"
Assert-Contains $decomposition.basis "not per-submission" "the basis says the decomposition is aggregate"

# The two counters are scraped over slightly different intervals, so the republish count can exceed
# the broker's. Reporting that as a negative residual is honest; clamping it to zero would hide the
# disagreement in the one number a reader would use to check the identity.
$inconsistent = Get-RabbitRedeliveryDecomposition -BrokerRedeliverCount 12 -StoredResultRepublishes 15
Assert-Equal -3 $inconsistent.rejudgedResidual "an inconsistent pair is reported as it stands"
Assert-False $inconsistent.consistent "and flagged as inconsistent rather than trusted"

$halfRead = Get-RabbitRedeliveryDecomposition -BrokerRedeliverCount 20 -StoredResultRepublishes $null
Assert-False $halfRead.available "one unread counter leaves the decomposition unavailable"
Assert-Null $halfRead.rejudgedResidual "and not as a residual equal to the whole count"

# --- the criteria precedence ------------------------------------------------------------------------
$mixed = @(
    (New-RabbitCriterion -Name "a" -Required "x" -Measured 1 -Satisfied $true)
    (New-RabbitCriterion -Name "b" -Required "y" -Measured $null -Satisfied $null)
)
$mixedVerdict = Get-RabbitCriteriaVerdict -Criteria $mixed -SatisfiedVerdict "yes" -UnsatisfiedVerdict "no"
Assert-Equal "unavailable" $mixedVerdict.verdict "an unmeasured criterion with nothing false leaves the answer unavailable"
Assert-Equal 1 $mixedVerdict.unavailableCriteria.Count "and the unmeasured criterion is named"

$withAFalse = @(
    (New-RabbitCriterion -Name "a" -Required "x" -Measured 1 -Satisfied $true)
    (New-RabbitCriterion -Name "b" -Required "y" -Measured $null -Satisfied $null)
    (New-RabbitCriterion -Name "c" -Required "z" -Measured 0 -Satisfied $false)
)
$falseWins = Get-RabbitCriteriaVerdict -Criteria $withAFalse -SatisfiedVerdict "yes" -UnsatisfiedVerdict "no"
Assert-Equal "no" $falseWins.verdict "one measured false decides the conjunction whatever the unmeasured ones say"
Assert-Equal 1 $falseWins.failedCriteria.Count "the false criterion is named"
Assert-Equal "c" $falseWins.failedCriteria[0] "and it is the right one"

$allTrue = @(
    (New-RabbitCriterion -Name "a" -Required "x" -Measured 1 -Satisfied $true)
    (New-RabbitCriterion -Name "b" -Required "y" -Measured 2 -Satisfied $true)
)
$satisfied = Get-RabbitCriteriaVerdict -Criteria $allTrue -SatisfiedVerdict "yes" -UnsatisfiedVerdict "no"
Assert-Equal "yes" $satisfied.verdict "every criterion satisfied is the satisfied verdict"

# --- single-node-sustainable -------------------------------------------------------------------------
function Get-SustainableVerdict {
    param(
        $ActiveWorkAtKill = $true,
        $ConsumerDropObserved = $true,
        $OfferedRateHeld = $true,
        $NodeDownResultRps = 47.5,
        $OfferedRps = 50,
        $BacklogContinuouslyGrowing = $false,
        $BacklogNormalizedBeforeRestart = $true,
        $IntegrityPassed = $true
    )
    return Get-RabbitSingleNodeSustainableVerdict -ActiveWorkAtKill $ActiveWorkAtKill `
        -ConsumerDropObserved $ConsumerDropObserved -OfferedRateHeld $OfferedRateHeld `
        -NodeDownResultRps $NodeDownResultRps -OfferedRps $OfferedRps -NodeDownWindowSeconds 10 `
        -BacklogContinuouslyGrowing $BacklogContinuouslyGrowing `
        -BacklogNormalizedBeforeRestart $BacklogNormalizedBeforeRestart -IntegrityPassed $IntegrityPassed
}

$sustainable = Get-SustainableVerdict
Assert-Equal "single-node-sustainable" $sustainable.verdict "all seven criteria holding is a single-node-sustainable run"
Assert-Equal 7 $sustainable.criteria.Count "the conjunction has seven criteria"
Assert-Equal 0 $sustainable.failedCriteria.Count "none failed"
Assert-Equal 0 $sustainable.unavailableCriteria.Count "and none were unmeasured"
Assert-Equal 0.95 $sustainable.measured.resultToOfferedRatio "the ratio behind the throughput criterion is reported"

# 47.5/50 is exactly the 0.90 floor, and the floor is inclusive: the criterion is "at least 90%",
# so a run sitting on it is sustainable rather than one measurement away from it.
$onTheFloor = Get-SustainableVerdict -NodeDownResultRps 45
Assert-Equal "single-node-sustainable" $onTheFloor.verdict "exactly 90% of the offered rate satisfies the throughput criterion"

$belowFloor = Get-SustainableVerdict -NodeDownResultRps 44.9
Assert-Equal "not-single-node-sustainable" $belowFloor.verdict "below 90% the surviving node did not carry the load"
Assert-Equal "node-down-result-rps" $belowFloor.failedCriteria[0] "and the failing criterion is the throughput one"

$noWork = Get-SustainableVerdict -ActiveWorkAtKill $false
Assert-Equal "not-single-node-sustainable" $noWork.verdict "a kill with no active work is not a recovery measurement"
Assert-Equal "active-work-at-kill" $noWork.failedCriteria[0] "and it names that criterion"

$growing = Get-SustainableVerdict -BacklogContinuouslyGrowing $true
Assert-Equal "not-single-node-sustainable" $growing.verdict "a backlog that only rises through the outage is a pipeline losing ground"
Assert-Equal "backlog-not-continuously-growing" $growing.failedCriteria[0] "named as the backlog trend"

$normalizedLate = Get-SustainableVerdict -BacklogNormalizedBeforeRestart $false
Assert-Equal "not-single-node-sustainable" $normalizedLate.verdict "a normalisation that waited for the restart is not one node's capacity"

$lost = Get-SustainableVerdict -IntegrityPassed $false
Assert-Equal "not-single-node-sustainable" $lost.verdict "a run that lost a submission is not sustainable whatever its throughput was"

# An unmeasured criterion is not a failure. A run whose broker counters were lost must not be
# reported as having failed to sustain the load - that would be a measurement this run never made.
$unknown = Get-SustainableVerdict -NodeDownResultRps $null
Assert-Equal "unavailable" $unknown.verdict "an unmeasured throughput leaves the verdict unavailable"
Assert-Equal 1 $unknown.unavailableCriteria.Count "and names the criterion that was not measured"

$unknownWithFailure = Get-SustainableVerdict -NodeDownResultRps $null -IntegrityPassed $false
Assert-Equal "not-single-node-sustainable" $unknownWithFailure.verdict "a measured failure still decides the conjunction"

Assert-Contains $sustainable.basis "active work present at the kill" "the basis restates the conjunction rather than only naming it"
Assert-True ($sustainable.criteria[0].basis.Length -gt 0) "every criterion carries the basis it was decided on"

# --- fast-failover ------------------------------------------------------------------------------------
$fast = Get-RabbitFastFailoverVerdict -ThroughputRecoveredAt ([datetimeoffset]::Parse("2026-09-20T12:00:13.000Z")) `
    -FaultAt $faultAt -ConsumerDroppedAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.400Z")) `
    -FirstRedeliveryAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.250Z")) `
    -RestartRequestedAt ([datetimeoffset]::Parse("2026-09-20T12:00:25.000Z"))
Assert-Equal "fast-failover" $fast.verdict "a recovery 3s after the fault, before the restart, is fast failover"
Assert-Equal 3 $fast.criteria.Count "the conjunction has three criteria"
Assert-Equal 3.0 $fast.measured.secondsAfterFault "the fault-relative recovery is reported"
Assert-Equal 2.6 $fast.measured.secondsAfterConsumerDrop "and the consumer-drop-relative one beside it"

# The request states the throughput criterion against "the fault / the consumer drop", so either
# relative reading satisfies it - and which one carried it is reported rather than implied. Here the
# consumers are observed to fall 1.5s after the kill, so a recovery 5.8s after the fault is 4.3s
# after the drop and satisfies the criterion on that clock alone.
$dropClockOnly = Get-RabbitFastFailoverVerdict -ThroughputRecoveredAt ([datetimeoffset]::Parse("2026-09-20T12:00:15.800Z")) `
    -FaultAt $faultAt -ConsumerDroppedAt ([datetimeoffset]::Parse("2026-09-20T12:00:11.500Z")) `
    -FirstRedeliveryAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.250Z")) `
    -RestartRequestedAt ([datetimeoffset]::Parse("2026-09-20T12:00:25.000Z"))
Assert-Equal 5.8 $dropClockOnly.measured.secondsAfterFault "recovery 5.8s after the fault"
Assert-Equal 4.3 $dropClockOnly.measured.secondsAfterConsumerDrop "is 4.3s after the consumer drop, inside the window"
Assert-Equal "fast-failover" $dropClockOnly.verdict "and satisfies the criterion on that clock"

$slow = Get-RabbitFastFailoverVerdict -ThroughputRecoveredAt ([datetimeoffset]::Parse("2026-09-20T12:00:20.000Z")) `
    -FaultAt $faultAt -ConsumerDroppedAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.400Z")) `
    -FirstRedeliveryAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.250Z")) `
    -RestartRequestedAt ([datetimeoffset]::Parse("2026-09-20T12:00:25.000Z"))
Assert-Equal "not-fast-failover" $slow.verdict "a recovery 10s after the fault is not fast failover"
Assert-Equal "throughput-recovered-within-window" $slow.failedCriteria[0] "and the failing criterion is named"

$noRedelivery = Get-RabbitFastFailoverVerdict -ThroughputRecoveredAt ([datetimeoffset]::Parse("2026-09-20T12:00:13.000Z")) `
    -FaultAt $faultAt -ConsumerDroppedAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.400Z")) `
    -FirstRedeliveryAt $null -RestartRequestedAt ([datetimeoffset]::Parse("2026-09-20T12:00:25.000Z"))
Assert-Equal "unavailable" $noRedelivery.verdict "an unobserved redelivery leaves the verdict unavailable rather than satisfied"
Assert-Equal "first-redelivery-right-after-fault" $noRedelivery.unavailableCriteria[0] "and names it"

$waitedForRestart = Get-RabbitFastFailoverVerdict -ThroughputRecoveredAt ([datetimeoffset]::Parse("2026-09-20T12:00:30.000Z")) `
    -FaultAt $faultAt -ConsumerDroppedAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.400Z")) `
    -FirstRedeliveryAt ([datetimeoffset]::Parse("2026-09-20T12:00:10.250Z")) `
    -RestartRequestedAt ([datetimeoffset]::Parse("2026-09-20T12:00:25.000Z"))
Assert-Equal "not-fast-failover" $waitedForRestart.verdict "a recovery that arrived with the replacement node is not failover"
Assert-Contains ($waitedForRestart.failedCriteria -join ",") "recovery-began-without-restart" "and that is the criterion it fails"

# --- the backlog trend ------------------------------------------------------------------------------
$rising = @(
    New-BrokerSample -At "2026-09-20T12:00:11.000Z" -JudgeBacklog 10
    New-BrokerSample -At "2026-09-20T12:00:12.000Z" -JudgeBacklog 40
    New-BrokerSample -At "2026-09-20T12:00:13.000Z" -JudgeBacklog 70
    New-BrokerSample -At "2026-09-20T12:00:14.000Z" -JudgeBacklog 95
)
$growth = Get-RabbitBacklogGrowthVerdict -Samples $rising -From ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")) -To ([datetimeoffset]::Parse("2026-09-20T12:00:15.000Z"))
Assert-Equal "continuously-growing" $growth.verdict "a backlog that only rises through the window is growing"
Assert-True $growth.continuouslyGrowing "and the boolean agrees"
Assert-Equal 95 $growth.peakValue "the peak is reported beside the endpoints"
Assert-Equal 28.333333 $growth.riseRatePerSecond "the rate is reported as a rate over the window's own span, not as the raw rise"

# A backlog that peaks and then falls is the shape a recovering pipeline has. Calling that growth
# would fail a run for the one thing that shows it recovered.
$peakedAndFell = @(
    New-BrokerSample -At "2026-09-20T12:00:11.000Z" -JudgeBacklog 10
    New-BrokerSample -At "2026-09-20T12:00:12.000Z" -JudgeBacklog 95
    New-BrokerSample -At "2026-09-20T12:00:13.000Z" -JudgeBacklog 40
    New-BrokerSample -At "2026-09-20T12:00:14.000Z" -JudgeBacklog 10
)
$peaked = Get-RabbitBacklogGrowthVerdict -Samples $peakedAndFell -From ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")) -To ([datetimeoffset]::Parse("2026-09-20T12:00:15.000Z"))
Assert-Equal "not-continuously-growing" $peaked.verdict "a backlog that peaks and drains is not growing"
Assert-Equal 95 $peaked.peakValue "the peak is still reported"
Assert-Equal ([datetimeoffset]::Parse("2026-09-20T12:00:12.000Z")) $peaked.peakAt "with the instant it was observed at, which is not the end of the window"

# A trend read off two samples is not a statement about a window.
$tooFew = Get-RabbitBacklogGrowthVerdict -Samples @($rising[0], $rising[1]) -From ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")) -To ([datetimeoffset]::Parse("2026-09-20T12:00:15.000Z"))
Assert-Equal "unavailable" $tooFew.verdict "two samples cannot make a continuous trend"
Assert-Null $tooFew.continuouslyGrowing "and the boolean is unmeasured rather than false"
Assert-Contains $tooFew.basis "below the" "the reason names the floor"

# An omitted backlog field is a tick that cannot be read, not a backlog of zero.
$backlogOmitted = @(
    New-BrokerSample -At "2026-09-20T12:00:11.000Z" -JudgeBacklog 10
    New-BrokerSample -At "2026-09-20T12:00:12.000Z" -Omit @("judgeBacklog")
    New-BrokerSample -At "2026-09-20T12:00:13.000Z" -JudgeBacklog 70
)
$omittedGrowth = Get-RabbitBacklogGrowthVerdict -Samples $backlogOmitted -From ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")) -To ([datetimeoffset]::Parse("2026-09-20T12:00:15.000Z"))
Assert-Equal 2 $omittedGrowth.sampleCount "an omitted backlog leaves the tick out of the window rather than counting it as zero"
Assert-Equal "unavailable" $omittedGrowth.verdict "and leaves too few samples to call a trend"

$peak = Get-RabbitBacklogPeak -Samples $peakedAndFell -From ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z")) -To ([datetimeoffset]::Parse("2026-09-20T12:00:15.000Z"))
Assert-True $peak.available "the peak is available wherever the window holds a readable sample"
Assert-Equal 95 $peak.value "the peak is the largest observed value"
Assert-Equal ([datetimeoffset]::Parse("2026-09-20T12:00:12.000Z")) $peak.at "reported with its own instant"

$noPeak = Get-RabbitBacklogPeak -Samples @() -From $faultAt -To $nodeReadyAt
Assert-False $noPeak.available "an empty series has no peak"
Assert-Null $noPeak.value "and null rather than zero"

# --- the file the harness actually reads -------------------------------------------------------------
$directory = Join-Path ([System.IO.Path]::GetTempPath()) ("rabbit-fault-verdict-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $directory | Out-Null
try {
    $path = Join-Path $directory "rabbit-queue-samples.csv"
    @(
        "timestamp,epochMillis,phase,tickMs,ready,unacked,readyPlusUnacked,consumers,connections,channels,publish,deliver,ack,redeliver,deadReady,deadUnacked,deadLetters,deadConsumers,error"
        '2026-09-20T12:00:09.750Z,1,load,12.0,3,32,35,32,4,40,100,100,99,0,,,,,'
        '2026-09-20T12:00:10.250Z,2,load,12.0,9,16,25,16,4,24,120,104,103,4,,,,,'
    ) | Set-Content $path -Encoding utf8
    $rows = @(Import-Csv $path | ForEach-Object {
        [pscustomobject]@{
            at = [datetimeoffset]::Parse($_.timestamp)
            consumers = if ([string]::IsNullOrWhiteSpace($_.consumers)) { $null } else { [double]$_.consumers }
            redeliver = if ([string]::IsNullOrWhiteSpace($_.redeliver)) { $null } else { [double]$_.redeliver }
        }
    })
    $fromFile = Get-RabbitConsumerTransition -Samples $rows -FromConsumers 32 -ToConsumers 16
    Assert-True $fromFile.observed "the consumer transition is placeable from the file the sampler writes"
    $redeliveryFromFile = Get-RabbitFirstRedelivery -Samples $rows -From ([datetimeoffset]::Parse("2026-09-20T12:00:10.000Z"))
    Assert-True $redeliveryFromFile.observed "so is the first redelivery"
    Assert-Equal 4 $redeliveryFromFile.count "and its count is the delta from the baseline"

    # A blank cell is the sampler's way of saying a counter could not be read. Import-Csv turns it
    # into an empty string, which must not be read as a zero.
    $blankPath = Join-Path $directory "blank.csv"
    @(
        "timestamp,consumers,redeliver"
        "2026-09-20T12:00:09.750Z,32,"
        "2026-09-20T12:00:10.250Z,16,"
    ) | Set-Content $blankPath -Encoding utf8
    $blankRows = @(Import-Csv $blankPath)
    Assert-Equal "" $blankRows[0].redeliver "Import-Csv leaves an unread counter as an empty string"
    Assert-True ([string]::IsNullOrWhiteSpace($blankRows[0].redeliver)) "which is what the harness has to test for before parsing it as a number"
} finally {
    Remove-Item -Recurse -Force $directory -ErrorAction SilentlyContinue
}

# --- the coercion every reading passes through --------------------------------------------------------
# A CSV round trip is where a reading that was never taken becomes a zero, and a zero that was read
# becomes nothing. Both directions are checked here, because a verdict cannot tell them apart later.
Assert-Null (Get-RabbitNumberOrNull $null) "a cell that is not there is null"
Assert-Null (Get-RabbitNumberOrNull "") "an empty cell is null"
Assert-Null (Get-RabbitNumberOrNull "   ") "a whitespace cell is null"
Assert-Null (Get-RabbitNumberOrNull "n/a") "a cell that is not a number is null rather than zero"
Assert-Null (Get-RabbitNumberOrNull "12,5") "a comma decimal is refused rather than read as 125, which is what a culture-sensitive parse would make of it"
Assert-Equal 12.5 (Get-RabbitNumberOrNull "12.5") "a number is read"
Assert-Equal 0 (Get-RabbitNumberOrNull "0") "and a zero that was really read stays a zero"

Assert-Null (Get-RabbitInstantOrNull $null) "a missing instant is null"
Assert-Null (Get-RabbitInstantOrNull "") "an empty instant is null"
Assert-Null (Get-RabbitInstantOrNull "not-a-time") "an unparsable instant is null rather than a default instant"
$withOffset = Get-RabbitInstantOrNull "2026-09-20T12:00:00.000+00:00"
Assert-Equal 0 $withOffset.Offset.TotalHours "an instant that carries an offset keeps it"
$withoutOffset = Get-RabbitInstantOrNull "2026-09-20T12:00:00"
Assert-True ($null -ne $withoutOffset) "an offset-less instant is still read rather than dropped out of the series"

# --- the broker sampler's CSV as a series -------------------------------------------------------------
$queueRows = @(
    [pscustomobject]@{ timestamp = "2026-09-20T12:00:10.250+00:00"; phase = "load"; ready = "9"; unacked = "16"; readyPlusUnacked = "25"; consumers = "16"; connections = "4"; channels = "24"; publish = "120"; deliver = "104"; ack = "103"; redeliver = "4"; deadReady = "0"; deadUnacked = ""; deadLetters = ""; deadConsumers = "" }
    [pscustomobject]@{ timestamp = "2026-09-20T12:00:09.750+00:00"; phase = "load"; ready = "3"; unacked = "32"; readyPlusUnacked = "35"; consumers = "32"; connections = "4"; channels = "40"; publish = "100"; deliver = "100"; ack = "99"; redeliver = "0"; deadReady = "0"; deadUnacked = ""; deadLetters = ""; deadConsumers = "" }
    [pscustomobject]@{ timestamp = ""; phase = "load"; ready = "1"; unacked = "1"; readyPlusUnacked = "2"; consumers = "1"; connections = "1"; channels = "1"; publish = "1"; deliver = "1"; ack = "1"; redeliver = "0"; deadReady = "0"; deadUnacked = ""; deadLetters = ""; deadConsumers = "" }
)
$queueSeries = Get-RabbitQueueSampleSeries -Rows $queueRows
Assert-Equal 3 $queueSeries.rowCount "every row is counted, readable or not"
Assert-Equal 1 $queueSeries.unreadableTimestampRows "a row without a readable timestamp is counted rather than silently dropped"
Assert-Equal 2 @($queueSeries.samples).Count "and it does not enter the series"
Assert-Equal 32 $queueSeries.samples[0].consumers "the series is ordered by instant, not by the order the file happened to hold"
Assert-True ($queueSeries.firstAt -lt $queueSeries.lastAt) "the bounds span the ordered series"
Assert-Equal 0 $queueSeries.samples[0].deadReady "a counter that read zero is zero"
Assert-Null $queueSeries.samples[0].deadLetters "a counter the sampler could not read is null, not zero"
Assert-Equal 25 $queueSeries.samples[1].readyPlusUnacked "a composite counter is read as the sampler wrote it"
Assert-Equal 0 (Get-RabbitQueueSampleSeries -Rows $null).rowCount "a series that was not read has no rows rather than an error"

$resultRows = @(
    [pscustomobject]@{ at = "2026-09-20T12:00:10.250+00:00"; judgeBacklog = "6"; scoreboardPending = "0"; accepted = "120"; results = "114" }
    [pscustomobject]@{ at = "2026-09-20T12:00:09.750+00:00"; judgeBacklog = "3"; scoreboardPending = ""; accepted = "100"; results = "97" }
)
$resultSeries = Get-RabbitResultSampleSeries -Rows $resultRows
Assert-Equal 100 $resultSeries.samples[0].accepted "the result series is ordered by its own instant column"
Assert-Null $resultSeries.samples[0].scoreboardPending "an unread scoreboard count is null rather than zero"
Assert-Equal 6 $resultSeries.samples[1].judgeBacklog "a backlog that was read is a reading"
Assert-Equal 0 $resultSeries.unreadableTimestampRows "both timestamps were readable"

# --- the offered rate, from the generator's own buckets ------------------------------------------------
$second0 = [datetimeoffset]::Parse("2026-09-20T12:00:00+00:00")
$from = $second0
$to = $second0.AddSeconds(4)
$buckets = New-Object System.Collections.Generic.List[object]
foreach ($offset in 0..4) {
    $buckets.Add([pscustomobject]@{ epochSecond = $second0.AddSeconds($offset).ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" })
    $buckets.Add([pscustomobject]@{ epochSecond = $second0.AddSeconds($offset).ToUnixTimeSeconds(); request = "api-contest-login"; offered = "100" })
}
$buckets.Add([pscustomobject]@{ epochSecond = $second0.AddSeconds(-1).ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" })
$buckets.Add([pscustomobject]@{ epochSecond = $second0.AddSeconds(5).ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" })

# The list is handed over as an array: PowerShell 5.1 refuses @() around a generic List[object] with
# "Argument types do not match" - the @() itself throws, before the parameter is ever bound.
$offered = Get-RabbitOfferedRateFromBuckets -Rows $buckets.ToArray() -RequestName "api-contest-submit" -From $from -To $to
Assert-True $offered.available "five whole seconds of the submit request is a readable offered rate"
Assert-Equal 250 $offered.offered "the numerator is the submit name's own buckets, with the logins and the out-of-window seconds left out"
Assert-Equal 5 $offered.seconds "the denominator is the whole seconds the window spans"
Assert-Equal 50 $offered.rps "so the rate is the submit request's, not every request name's"
Assert-Equal 5 $offered.bucketCount "and one bucket per second was counted"

$sparse = @(
    [pscustomobject]@{ epochSecond = $second0.ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" }
    [pscustomobject]@{ epochSecond = $second0.AddSeconds(4).ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" }
)
$sparseRate = Get-RabbitOfferedRateFromBuckets -Rows $sparse -RequestName "api-contest-submit" -From $from -To $to
Assert-Equal 5 $sparseRate.seconds "a second in which nothing was logged is still part of the window"
Assert-Equal 20 $sparseRate.rps "so an interrupted offer reads as a lower rate rather than as a shorter window"

$short = @(
    [pscustomobject]@{ epochSecond = $second0.ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" }
    [pscustomobject]@{ epochSecond = $second0.AddSeconds(1).ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "50" }
)
$shortRate = Get-RabbitOfferedRateFromBuckets -Rows $short -RequestName "api-contest-submit" -From $from -To $to
Assert-False $shortRate.available "two whole seconds is below the floor a rate needs before it is reported"
Assert-Null $shortRate.rps "so the rate is null rather than a number that would then decide a verdict"
Assert-Contains $shortRate.basis "below the 5" "and the basis says why it was not reported"
$permissive = Get-RabbitOfferedRateFromBuckets -Rows $short -RequestName "api-contest-submit" -From $from -To $to -MinimumSeconds 2
Assert-True $permissive.available "the floor is a parameter rather than a hidden constant"

$missingName = Get-RabbitOfferedRateFromBuckets -Rows $sparse -RequestName "api-contest-nothing" -From $from -To $to
Assert-False $missingName.available "a request name with no buckets in the window has no rate"
Assert-Null $missingName.rps "and it is unavailable rather than zero"
Assert-Contains $missingName.basis "api-contest-nothing" "the basis names the request it looked for"

$blankOffered = @([pscustomobject]@{ epochSecond = $second0.ToUnixTimeSeconds(); request = "api-contest-submit"; offered = "" })
$blankRate = Get-RabbitOfferedRateFromBuckets -Rows $blankOffered -RequestName "api-contest-submit" -From $from -To $to
Assert-False $blankRate.available "an unread offered count is not a second in which nothing was offered"
$noBucketRows = Get-RabbitOfferedRateFromBuckets -Rows $null -RequestName "api-contest-submit" -From $from -To $to
Assert-False $noBucketRows.available "unread buckets have no rate either"

# --- a window's own result rate, from the cumulative counts -------------------------------------------
$fault = [datetimeoffset]::Parse("2026-09-20T12:00:00+00:00")
$windowSamples = @(
    [pscustomobject]@{ at = $fault; results = 1000 }
    [pscustomobject]@{ at = $fault.AddSeconds(1); results = 1010 }
    [pscustomobject]@{ at = $fault.AddSeconds(2); results = 1020 }
    [pscustomobject]@{ at = $fault.AddSeconds(3); results = $null }
    [pscustomobject]@{ at = $fault.AddSeconds(5); results = 1250 }
)
$whole = Get-RabbitWindowResultRps -Samples $windowSamples -From $fault -To $fault.AddSeconds(5)
Assert-True $whole.available "a window with readable readings has a rate"
Assert-Equal 50 $whole.resultRps "two cumulative readings and the span between them is the rate"
Assert-Equal 4 $whole.sampleCount "a reading that was not taken is not a sample in the window"
Assert-Equal 1000 $whole.firstValue "the first reading is the window's own first, not the series' first"

$headed = Get-RabbitWindowResultRps -Samples $windowSamples -From $fault -To $fault.AddSeconds(5) -ExcludeHeadSeconds 2
Assert-True $headed.available "the head-excluded window is still readable"
Assert-Equal 2 $headed.sampleCount "the readings inside the excluded head are out of the window"
Assert-Equal ([math]::Round(230 / 3, 6)) $headed.resultRps "and the rate is the part the dead node had not already set up"
Assert-True ($headed.resultRps -gt $whole.resultRps) "which is why the head is excluded: without it a fail-stop run credits the survivor with the dead node's work"

$thin = Get-RabbitWindowResultRps -Samples @([pscustomobject]@{ at = $fault; results = 1000 }) -From $fault -To $fault.AddSeconds(5)
Assert-False $thin.available "one reading is not a rate"
Assert-Null $thin.resultRps "so the rate is null rather than zero"
Assert-Contains $thin.basis "below the 2" "and the basis says how thin the window was"

$noWindow = Get-RabbitWindowResultRps -Samples $windowSamples -From $null -To $null
Assert-False $noWindow.available "a window without a start has no rate"
Assert-Null $noWindow.resultRps "and none is invented for it"

$sameInstant = @(
    [pscustomobject]@{ at = $fault; results = 10 }
    [pscustomobject]@{ at = $fault; results = 20 }
)
$degenerate = Get-RabbitWindowResultRps -Samples $sameInstant -From $fault -To $fault.AddSeconds(5)
Assert-False $degenerate.available "two readings at one instant have no span to divide by"
Assert-Null $degenerate.resultRps "so the rate is null"

# The readers are handed Import-Csv's output in the harness, so the round trip is checked on a file:
# a blank cell arrives as an empty string, which is the shape this coercion has to survive.
$readersDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("rabbit-fault-readers-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $readersDirectory | Out-Null
try {
    $queueCsv = Join-Path $readersDirectory "rabbit-queue-samples.csv"
    @(
        "timestamp,phase,ready,unacked,readyPlusUnacked,consumers,connections,channels,publish,deliver,ack,redeliver,deadReady,deadUnacked,deadLetters,deadConsumers"
        "2026-09-20T12:00:09.750+00:00,load,3,32,35,32,4,40,100,100,99,0,0,,,,"
        "2026-09-20T12:00:10.250+00:00,load,9,16,25,16,4,24,120,104,103,4,0,,,,"
    ) | Set-Content $queueCsv -Encoding utf8
    $queueFromFile = Get-RabbitQueueSampleSeries -Rows @(Import-Csv $queueCsv)
    Assert-Equal 0 $queueFromFile.samples[0].deadReady "a zero that came through the CSV is still a zero"
    Assert-Null $queueFromFile.samples[0].deadLetters "and an unread counter that came through the CSV is still null"
    Assert-Equal 4 $queueFromFile.samples[1].redeliver "the redelivery counter survives the round trip"

    $resultCsv = Join-Path $readersDirectory "recovery-samples.csv"
    @(
        "at,judgeBacklog,scoreboardPending,accepted,results"
        "2026-09-20T12:00:09.750+00:00,3,,100,97"
        "2026-09-20T12:00:10.250+00:00,6,0,120,114"
    ) | Set-Content $resultCsv -Encoding utf8
    $resultFromFile = Get-RabbitResultSampleSeries -Rows @(Import-Csv $resultCsv)
    Assert-Null $resultFromFile.samples[0].scoreboardPending "an unread scoreboard count is null through the CSV too"
    Assert-Equal 6 $resultFromFile.samples[1].judgeBacklog "while a backlog that was read is a reading"
} finally {
    Remove-Item -Recurse -Force $readersDirectory -ErrorAction SilentlyContinue
}

if ($script:failures.Count -gt 0) {
    Write-Host "RabbitFaultRecoveryVerdict: $($script:failures.Count) of $($script:checks) checks failed"
    foreach ($failure in $script:failures) { Write-Host "  FAILED $failure" }
    exit 1
}
Write-Host "RabbitFaultRecoveryVerdict: all $($script:checks) checks passed"
exit 0
