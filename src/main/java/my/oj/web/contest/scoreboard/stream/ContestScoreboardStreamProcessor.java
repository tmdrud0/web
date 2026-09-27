package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.CheckpointAdvance;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 *       retention, a batch failed below this delivery, or Redis rolled back behind the position.
 *       {@link ContestScoreboardRecoveryStrategy#rebuildHistory} decides whether the mode's own basis
 *       covers the range below it. Only if it does may the checkpoint move there.</li>
 *   <li>A delivery above a range a failed batch left unapplied - never an ordinary step, and never the
 *       first offset of the stream however unset the checkpoint is. That range is a live delivery the
 *       broker will not hand back, so the resubscribe that re-reads it is its repair; asking a mode
 *       about it is asking whether the mode's basis happens to hold the same results, which for a
 *       basis written at apply time it does not. See
 *       {@link ContestScoreboardStreamPosition#unappliedFrom()}.</li>
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

    /**
     * Why a delivery sat above the range the checkpoint could not reach, so nothing is reported as
     * something it is not.
     *
     * <p>The three are counted apart for a reason: {@code contest.scoreboard.stream.offset.gaps}
     * counts requested offsets that were outside retained history, and a rollback or a failed batch is
     * not that. Neither is a lost result either - the first was taken away from the standings and can
     * be rebuilt, the second was never applied and is re-read by a resubscribe - so each says what it
     * is in the message instead of borrowing the retention vocabulary.</p>
     */
    private enum GapReason {

        /** The checkpoint is no longer retained, so the stream cannot be read from where it stopped. */
        RETENTION("no longer retained"),

        /** Redis rolled back behind what this process applied. */
        ROLLBACK("behind what this process applied"),

        /** A failed batch left the offsets below this delivery unapplied. */
        UNAPPLIED("left unapplied by a failed batch");

        private final String description;

        GapReason(String description) {
            this.description = description;
        }
    }

    private final ContestScoreboardApplier applier;
    private final ContestScoreboardAppliedAtCompletion completion;
    private final ContestScoreboardStreamPosition position;
    private final ContestScoreboardRecoveryStrategy strategy;
    private final ContestScoreboardStreamMetrics metrics;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardExperimentTrace trace;

    ContestScoreboardStreamProcessor(
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamPosition position,
            ContestScoreboardRecoveryStrategy strategy,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardApplyLock applyLock
    ) {
        this(applier, completion, position, strategy, metrics, applyLock, ContestScoreboardExperimentTrace.NOOP);
    }

    @Autowired
    ContestScoreboardStreamProcessor(
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamPosition position,
            ContestScoreboardRecoveryStrategy strategy,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardApplyLock applyLock,
            ObjectProvider<ContestScoreboardExperimentTrace> trace
    ) {
        this(applier, completion, position, strategy, metrics, applyLock,
                trace.getIfAvailable(() -> ContestScoreboardExperimentTrace.NOOP));
    }

    ContestScoreboardStreamProcessor(
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamPosition position,
            ContestScoreboardRecoveryStrategy strategy,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardApplyLock applyLock,
            ContestScoreboardExperimentTrace trace
    ) {
        this.applier = applier;
        this.completion = completion;
        this.position = position;
        this.strategy = strategy;
        this.metrics = metrics;
        this.applyLock = applyLock;
        this.trace = trace;
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
        boolean aboveUnappliedRange = unappliedFrom >= 0L && firstDelivery > unappliedFrom;
        if (aboveUnappliedRange) {
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
        return anchorAfterRebuild(checkpoint, firstDelivery,
                gapReason(aboveUnappliedRange, checkpoint, applied));
    }

    /**
     * Why the delivery could not be an ordinary forward step.
     *
     * <p>An outstanding unapplied range wins even when the checkpoint is also behind what this process
     * applied, because it is what makes <em>this delivery</em> a question - and because what the mode
     * is asked about the range is then whether its basis holds offsets that were never applied, which
     * is a different question from the one a rollback asks.</p>
     */
    private static GapReason gapReason(boolean aboveUnappliedRange, long checkpoint, long applied) {
        if (aboveUnappliedRange) {
            return GapReason.UNAPPLIED;
        }
        return checkpoint < applied ? GapReason.ROLLBACK : GapReason.RETENTION;
    }

    /**
     * Asks the mode's basis about the range below a delivery that jumped past the checkpoint.
     *
     * <p>A delivery above the checkpoint means the offsets between them are not readable from the
     * stream, so the results they carried exist only in whatever the mode treats as its history. The
     * mode is the only thing that may say whether they are there. Most modes must cover the range
     * before progress; redis-seq may instead allow live progress for a rollback wholly inside this
     * JVM's applied history while its idempotent repair continues asynchronously. A range containing
     * an offset never applied here is still refused.</p>
     *
     * <p>The range handed over states both of its ends: the offset just below this delivery, and the
     * highest offset this process applied. Its answer is read against the same ends, so a
     * reconstruction that stopped short of them is not taken for one that reached them.</p>
     */
    private CheckpointAdvance anchorAfterRebuild(long checkpoint, long firstDelivery, GapReason reason) {
        if (reason == GapReason.RETENTION) {
            // Only a checkpoint that is gone from retention is what this counter counts. A rollback is
            // the standings moving while the broker kept every offset, and a failed batch is a range
            // the broker still serves and only this consumer cannot be handed again.
            metrics.recordOffsetGap();
        }
        ContestScoreboardRecoveryStrategy.LostRange range = new ContestScoreboardRecoveryStrategy.LostRange(
                checkpoint,
                firstDelivery - 1L,
                position.highestAppliedOffset(),
                position.rebuiltThrough()
        );
        long askedAt = trace.enabled() ? System.currentTimeMillis() : 0L;
        ContestScoreboardRecoveryStrategy.Outcome outcome = strategy.rebuildHistory(range);
        if (trace.enabled()) {
            trace.recovery(new ContestScoreboardExperimentTrace.RecoveryRecord(
                    ContestScoreboardExperimentTrace.RecoveryEvent.GAP,
                    Thread.currentThread().getName(),
                    askedAt,
                    -1L,
                    System.currentTimeMillis(),
                    -1,
                    reason.name().toLowerCase(Locale.ROOT) + " checkpoint=" + checkpoint
                            + " delivery=" + firstDelivery,
                    outcome.label()
            ));
        }
        boolean mayAdvance = outcome.covers()
                || (reason == GapReason.ROLLBACK
                && outcome == ContestScoreboardRecoveryStrategy.Outcome.LIVE_PROGRESS);
        if (!mayAdvance) {
            throw new IllegalStateException(
                    "Scoreboard stream checkpoint " + checkpoint + " is "
                            + reason.description
                            + " and the " + strategy.mode().propertyValue()
                            + " basis did not rebuild the range below " + firstDelivery
                            + " (" + outcome.label() + ")"
                            + "; the batch is left unapplied so the checkpoint does not move past results "
                            + "the standings never saw");
        }
        position.markAnchorVerified();
        log.warn("Scoreboard stream checkpoint {} was {}; the {} basis returned {} for the range below {} "
                        + "and the live checkpoint may now move there",
                checkpoint,
                reason.description,
                strategy.mode().propertyValue(),
                outcome.label(),
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
        long answeredAt = trace.enabled() ? System.currentTimeMillis() : 0L;
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
        if (trace.enabled()) {
            // Recorded as soon as the applier answered for the whole batch, which is when the standings
            // reflect it - before the MySQL completion below, which is bookkeeping about a write that
            // has already happened.
            trace.liveBatchApplied(answeredAt, batch.stream()
                    .map(event -> new ContestScoreboardExperimentTrace.LiveEvent(
                            event.offset(), event.message().submissionId(), event.message().judgedAt()))
                    .toList());
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
