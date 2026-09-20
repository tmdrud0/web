package my.oj.web.contest.scoreboard.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the consumer is, and how far the scoreboard's history has been rebuilt in this JVM.
 *
 * <p>Both the listener and the supervisor need this, and the supervisor is built on top of the
 * listener's container - so it lives beside them rather than inside either. It is also the one piece
 * of state that outlives a Redis rollback: Redis rewinds, this does not, which is what makes it the
 * only record of how far back a rollback reached.</p>
 *
 * <p>Package-private and small on purpose. Every field here answers a question the recovery decision
 * asks, and nothing else belongs in it.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
class ContestScoreboardStreamPosition {

    /** Highest offset this JVM has applied, or {@code -1} before the first batch. */
    private final AtomicLong highestAppliedOffset = new AtomicLong(-1L);

    private final AtomicLong failedBatches = new AtomicLong();

    /**
     * Highest offset whose range a completed history rebuild in this JVM covers, or {@code -1}.
     *
     * <p>Read by the live path so a range the supervisor already rebuilt is not rebuilt a second
     * time by the delivery that anchors past it.</p>
     */
    private final AtomicLong rebuiltThrough = new AtomicLong(-1L);

    /**
     * Whether the consumer's position relative to the checkpoint has been established, so the offsets
     * it delivers may be applied without asking whether a range was skipped.
     *
     * <p>Established once per consumer position: the consumer is handed an offset at or below the
     * checkpoint, which means it is reading from a place the scoreboard already reached and will walk
     * forward through everything retained in between. A resubscribe, and a rollback, each make the
     * position a fresh question again.</p>
     */
    private final AtomicBoolean anchorVerified = new AtomicBoolean();

    /**
     * Lowest offset a failed batch left unapplied, or {@code -1} when every delivery consumed has been
     * applied.
     *
     * <p>The un-verified anchor is what makes the next delivery a question; this is what the question
     * is about. A delivery above this offset carries a claim over a range that no apply ever wrote, and
     * the mode's history basis is not the authority on it - the batch that failed was a live delivery,
     * and what repairs it is the resubscribe that re-reads it, not a replay of what MySQL holds.</p>
     *
     * <p>Held in memory on purpose, and it does not need to be durable: a restarted consumer resumes at
     * the checkpoint inclusive, which is at or below this offset, so the range is re-read - and if it
     * still cannot be applied, it is recorded again before anything above it is judged.</p>
     */
    private final AtomicLong unappliedFrom = new AtomicLong(-1L);

    long highestAppliedOffset() {
        return highestAppliedOffset.get();
    }

    /**
     * Records a batch that was applied, and forgets the range it covered.
     *
     * <p>The checkpoint only moves on an apply, so a checkpoint that reached this range is proof the
     * range is in the standings - which is what the resubscribe at the checkpoint produces when the
     * delivery it re-reads is applied.</p>
     */
    void recordAppliedOffset(long offset) {
        highestAppliedOffset.set(offset);
        unappliedFrom.updateAndGet(current -> current >= 0L && current <= offset ? -1L : current);
    }

    /**
     * Records the range a failed batch left unapplied, from the offset the batch stopped at.
     *
     * <p>The lowest such offset wins: a range that failed again is the same range still outstanding,
     * not a new one, and a later failure above it does not release the earlier one.</p>
     */
    void recordUnappliedRange(long lowestUnappliedOffset) {
        if (lowestUnappliedOffset < 0L) {
            return;
        }
        unappliedFrom.accumulateAndGet(lowestUnappliedOffset,
                (current, candidate) -> current < 0L ? candidate : Math.min(current, candidate));
    }

    long unappliedFrom() {
        return unappliedFrom.get();
    }

    /** How many batches have failed, so the supervisor can tell a new failure from one it has handled. */
    long failedBatches() {
        return failedBatches.get();
    }

    void recordFailedBatch() {
        failedBatches.incrementAndGet();
    }

    /**
     * Records the position a restarted consumer resumes from.
     *
     * <p>Clears the anchor verification with it: a new position is a new question, whatever the old
     * one had established. A range an earlier batch left unapplied is deliberately left outstanding -
     * the resume is at or below it, so the re-read either applies it or records it again, and until
     * then nothing above it may be applied.</p>
     */
    void resumeAt(long offset) {
        highestAppliedOffset.set(offset);
        anchorVerified.set(false);
    }

    boolean anchorVerified() {
        return anchorVerified.get();
    }

    void markAnchorVerified() {
        anchorVerified.set(true);
    }

    /**
     * Forgets the verification without moving the position.
     *
     * <p>Used when Redis rolls back behind this JVM, because the consumer that keeps running is now
     * reading from a position the scoreboard can no longer reach, so the next delivery has to be
     * judged again rather than applied as an ordinary forward step.</p>
     */
    void clearAnchorVerified() {
        anchorVerified.set(false);
    }

    long rebuiltThrough() {
        return rebuiltThrough.get();
    }

    void markRebuiltThrough(long offset) {
        rebuiltThrough.accumulateAndGet(offset, Math::max);
    }
}
