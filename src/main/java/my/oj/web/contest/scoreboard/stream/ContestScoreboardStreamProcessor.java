package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.CheckpointAdvance;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one batch of stream deliveries into scoreboard writes, deciding what the checkpoint may claim.
 *
 * <h2>Offsets are not consecutive integers</h2>
 *
 * <p>A RabbitMQ stream hands over the offsets that exist, and the ones that exist are whatever was
 * published. Nothing here may assume the delivery after {@code 5} is {@code 6}, so the checkpoint is
 * never advanced by arithmetic - it moves to the offset a delivery actually carried, and the only
 * question asked about a delivery is whether the range beneath it was accounted for.</p>
 *
 * <p>The question has one answer per consumer position, and the position is established by what the
 * consumer was handed. {@link ContestScoreboardStreamLifecycle} starts it at the stored checkpoint
 * inclusive when there is one and at the beginning of retention when there is not, so:</p>
 *
 * <ul>
 *   <li>No checkpoint at all, and nothing outstanding - the first delivery becomes the checkpoint,
 *       whatever number it carries. Nothing requires a stream to begin at zero or one.</li>
 *   <li>A delivery at or below the checkpoint - the consumer is reading from at or behind where the
 *       scoreboard already is, so it will walk forward through everything retained in between and
 *       nothing was skipped. The anchor is verified, and the script absorbs the duplicate.</li>
 *   <li>A delivery above the checkpoint, with the anchor already verified - an ordinary forward step.
 *       Only monotonic increase is checked, and that inside the script.</li>
 *   <li>A delivery above the checkpoint, with the anchor not verified - the checkpoint is gone from
 *       retention. {@link ContestScoreboardRecoveryStrategy#rebuildHistory} decides whether the mode's
 *       own basis covers the range below it. Only if it does may the checkpoint move there.</li>
 *   <li>A delivery above a range a failed batch left unapplied - never an ordinary step, and never the
 *       first offset of the stream however unset the checkpoint is. That range is a live delivery the
 *       broker will not hand back, so it exists nowhere else and no basis may vouch for it; the
 *       resubscribe re-reads it instead. See {@link ContestScoreboardStreamPosition#unappliedFrom()}.</li>
 * </ul>
 *
 * <p>What this cannot see is a broker that skips offsets mid-connection: the position was verified
 * once, at the boundary where the consumer was handed its start, and a delivery above the checkpoint
 * afterwards is treated as an ordinary one. {@code contest.scoreboard.applied.offset} against the tail
 * monitor's {@code contest.scoreboard.pending} is the only observation of that, and it is recorded as a
 * remaining risk rather than defended against here.</p>
 *
 * <p>The decision runs outside the apply lock on purpose. Answering it can mean a full replay from
 * MySQL, which takes that lock per chunk - holding it while asking would deadlock against the
 * supervisor asking the same question while holding the gate.</p>
 */
@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
@Slf4j
class ContestScoreboardStreamProcessor {

    private final ContestScoreboardApplier applier;
    private final ContestScoreboardAppliedAtCompletion completion;
    private final ContestScoreboardStreamPosition position;
    private final ContestScoreboardRecoveryStrategy strategy;
    private final ContestScoreboardStreamMetrics metrics;
    private final ContestScoreboardApplyLock applyLock;

    ContestScoreboardStreamProcessor(
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamPosition position,
            ContestScoreboardRecoveryStrategy strategy,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardApplyLock applyLock
    ) {
        this.applier = applier;
        this.completion = completion;
        this.position = position;
        this.strategy = strategy;
        this.metrics = metrics;
        this.applyLock = applyLock;
    }

    long process(List<ContestScoreboardStreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return applier.currentStreamOffset();
        }
        List<ContestScoreboardStreamEvent> batch = List.copyOf(events);
        CheckpointAdvance advance = resolveAdvance(batch);
        return applyLock.withLock(() -> applyBatch(batch, advance));
    }

    /**
     * Decides what the first forward step in this batch may claim.
     *
     * @return {@link CheckpointAdvance#ANCHOR} when the checkpoint is being established or moved to an
     *         offset the mode's basis was just confirmed to cover, {@link CheckpointAdvance#CONTINUE}
     *         when the delivery is an ordinary step from an already verified position
     * @throws IllegalStateException when the delivery sits above a checkpoint whose range no basis has
     *         rebuilt. The batch is left unapplied and the checkpoint does not move, which is the
     *         loud failure every mode is required to produce rather than a silently bridged gap
     */
    private CheckpointAdvance resolveAdvance(List<ContestScoreboardStreamEvent> batch) {
        long checkpoint = applier.currentStreamOffset();
        long applied = position.highestAppliedOffset();
        if (checkpoint >= 0L && checkpoint < applied) {
            // Redis rolled back behind this JVM. The supervisor answers a rollback on its own
            // cadence, so a delivery can arrive first; judging it against the pre-rollback anchor
            // would apply a step the standings can no longer reach. Forgetting the anchor makes the
            // next delivery a fresh question, whatever the supervisor does or does not do.
            position.clearAnchorVerified();
        }
        long firstDelivery = batch.get(0).offset();
        long unappliedFrom = position.unappliedFrom();
        if (unappliedFrom >= 0L && firstDelivery > unappliedFrom) {
            // A batch was left unapplied and this delivery begins above it. Between the two lies a
            // range no apply ever wrote, and the delivery's own offset is a claim over it: applying
            // it would move the checkpoint past results the standings never saw, with the failure
            // the only record that anything was skipped.
            if (checkpoint < 0L) {
                // There is no checkpoint, so there is no range below the delivery to hand a mode and
                // no basis that could vouch for the offsets between. The delivery is refused and the
                // checkpoint stays unset - the loud failure every mode owes for an unbuilt range.
                metrics.recordUnappliedRefusal();
                throw new IllegalStateException("Scoreboard stream delivery at offset " + firstDelivery
                        + " starts above offset " + unappliedFrom + ", which a failed batch left "
                        + "unapplied and nothing rebuilt; the delivery is refused so the checkpoint "
                        + "does not move past results the standings never saw");
            }
            // With a checkpoint there is a range and a mode that owns it, so the question is asked of
            // it rather than answered here - which means the anchor must not be trusted to carry the
            // delivery forward as an ordinary step.
            position.clearAnchorVerified();
        }
        if (checkpoint < 0L) {
            // Nothing to be discontinuous with, so the first offset that exists becomes the
            // checkpoint. It is not required to be 0 or 1.
            position.markAnchorVerified();
            log.info("Scoreboard has no stream checkpoint; adopting the first delivered offset {} as the anchor",
                    firstDelivery);
            return CheckpointAdvance.ANCHOR;
        }
        if (position.anchorVerified()) {
            return CheckpointAdvance.CONTINUE;
        }
        if (firstDelivery <= checkpoint) {
            // The consumer was handed an offset at or below the checkpoint, so it is reading from a
            // place the scoreboard already reached and will walk forward through everything retained
            // in between. Nothing can be skipped from here, so the position is verified.
            position.markAnchorVerified();
            log.info("Scoreboard stream consumer resumed at {} against checkpoint {}; treating the position "
                            + "as anchored and reading forward",
                    firstDelivery, checkpoint);
            return CheckpointAdvance.CONTINUE;
        }
        return anchorAfterRebuild(checkpoint, firstDelivery, checkpoint < applied);
    }

    /**
     * Asks the mode's basis about the range below a delivery that jumped past the checkpoint.
     *
     * <p>A delivery above the checkpoint means the offsets between them are not readable from the
     * stream, so the results they carried exist only in whatever the mode treats as its history. The
     * mode is the only thing that may say whether they are there.</p>
     *
     * <p>Two situations produce this jump and they are counted apart. A checkpoint that is no longer
     * retained is a retention gap: nothing was lost from the standings, but the stream can no longer
     * be read from where the scoreboard stopped. A checkpoint below what this JVM applied is a
     * rollback: the standings lost results the stream still has, and the supervisor's pass answers it
     * per mode. Only the first is what {@code contest.scoreboard.stream.offset.gaps} counts.</p>
     */
    private CheckpointAdvance anchorAfterRebuild(long checkpoint, long firstDelivery, boolean rolledBack) {
        if (!rolledBack) {
            metrics.recordOffsetGap();
        }
        ContestScoreboardRecoveryStrategy.LostRange range = new ContestScoreboardRecoveryStrategy.LostRange(
                checkpoint,
                checkpoint + 1L,
                position.highestAppliedOffset(),
                position.rebuiltThrough()
        );
        if (!strategy.rebuildHistory(range)) {
            throw new IllegalStateException(
                    "Scoreboard stream checkpoint " + checkpoint + " is "
                            + (rolledBack ? "behind what this process applied" : "no longer retained")
                            + " and the " + strategy.mode().propertyValue()
                            + " basis did not rebuild the range below " + firstDelivery
                            + "; the batch is left unapplied so the checkpoint does not move past results "
                            + "the standings never saw");
        }
        position.markAnchorVerified();
        log.warn("Scoreboard stream checkpoint {} was {}; the {} basis rebuilt the range below {} "
                        + "and the checkpoint may now move there",
                checkpoint,
                rolledBack ? "behind what this process applied" : "not retained",
                strategy.mode().propertyValue(),
                firstDelivery);
        return CheckpointAdvance.ANCHOR;
    }

    private long applyBatch(List<ContestScoreboardStreamEvent> batch, CheckpointAdvance advance) {
        long checkpoint = applier.currentStreamOffset();
        List<ContestScoreboardApplier.ApplyRequest> requests = new ArrayList<>(batch.size());
        boolean anchorSpent = false;
        for (ContestScoreboardStreamEvent event : batch) {
            CheckpointAdvance eventAdvance;
            if (event.offset() <= checkpoint) {
                // At or below the checkpoint: a retained anchor re-delivered on a resubscribe, or an
                // event a rollback already rewound past. The script returns the stored offset without
                // touching the standings, so the claim it carries is never read.
                eventAdvance = CheckpointAdvance.CONTINUE;
            } else if (advance == CheckpointAdvance.ANCHOR && !anchorSpent) {
                eventAdvance = CheckpointAdvance.ANCHOR;
                anchorSpent = true;
            } else {
                eventAdvance = CheckpointAdvance.CONTINUE;
            }
            requests.add(ContestScoreboardApplier.ApplyRequest.stream(
                    event.offset(),
                    event.update(),
                    eventAdvance
            ));
        }

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests);
        ContestScoreboardApplier.ApplyResult failed = results.stream()
                .filter(result -> !result.succeeded())
                .findFirst()
                .orElse(null);
        if (failed != null || results.size() != requests.size()) {
            // The batch is unapplied from the first request the applier did not answer for: the one it
            // reported, or - when it reported none - the first one it left unanswered. Recorded before
            // the failure is thrown, because what the broker does with a requeueing rejection is
            // nothing at all, so this range is only ever re-read by a resubscribe, and until it is, no
            // delivery above it may be applied. A stream request's correlation id is the offset the
            // delivery carried - see ApplyRequest.stream.
            String detail = failed == null
                    ? "batch stopped before every event was applied"
                    : failed.errorMessage();
            long unappliedFrom = failed != null
                    ? failed.correlationId()
                    : offsetOf(requests, results.size());
            position.recordUnappliedRange(unappliedFrom);
            throw new IllegalStateException("Failed to apply scoreboard stream batch: " + detail);
        }

        completion.complete(batch.stream().map(event -> event.message().submissionId()).toList());
        long appliedOffset = applier.currentStreamOffset();
        // The checkpoint is recorded here rather than by the listener, because this is where it was
        // read back from the applier: the position and the checkpoint have to be the same claim, and
        // recording the range that was applied is what releases any range a failure left outstanding.
        position.recordAppliedOffset(appliedOffset);
        // Count offsets only after MySQL completion. The metric's own watermark deliberately
        // lags Redis when a batch fails halfway, so the successful retry counts those earlier
        // Lua writes once; a delivery repeated after ACK loss counts zero.
        metrics.recordApplied(batch.stream().map(ContestScoreboardStreamEvent::offset).toList(), appliedOffset);
        return appliedOffset;
    }

    /** The stream offset a request carried, or {@code -1} when it carried none. */
    private static long offsetOf(List<ContestScoreboardApplier.ApplyRequest> requests, int index) {
        if (index >= requests.size()) {
            return -1L;
        }
        Long offset = requests.get(index).streamOffset();
        return offset == null ? -1L : offset;
    }
}
