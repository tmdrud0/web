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

    long highestAppliedOffset() {
        return highestAppliedOffset.get();
    }

    void recordAppliedOffset(long offset) {
        highestAppliedOffset.set(offset);
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
     * one had established.</p>
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
