package my.oj.web.contest.scoreboard.recovery;

import java.util.Locale;

/**
 * What one recovery mode does about the history the live consumer cannot reach.
 *
 * <p>Every mode delivers new results the same way - the RabbitMQ stream and the one Lua write path
 * are untouched by the mode. What a mode owns is the answer to a question the live path cannot answer
 * on its own: the checkpoint Redis holds is behind the offsets the broker is about to hand over, so
 * which results are missing from the standings, and what puts them back?</p>
 *
 * <p>That question is asked in two places, and both go through this type so a mode cannot recover
 * through a mechanism it does not own.</p>
 *
 * <ul>
 *   <li>{@link #recoversHistoryBeforeConsuming()} is the cold-start half. Only {@code stream-offset}
 *       treats re-reading from the stored checkpoint as its history recovery, so only it may begin
 *       consuming as the JVM comes up. The other two have their own basis to run first, and a consumer
 *       that began before it would put the history back through the stream - the basis of a mode they
 *       are not running. See {@link ContestScoreboardRecoveryCutover}.</li>
 *   <li>{@link #rewindsOnCheckpointRegression()} is the supervisor's half. The checkpoint moved
 *       backwards, and only {@code stream-offset} answers it by re-reading the stream. The other two
 *       answer false and keep the consumer where it is, because their history basis is MySQL or the
 *       sequence, not the stream position - and a consumer that is never stopped cannot miss a result
 *       published while the rebuild runs.</li>
 *   <li>{@link #rebuildHistory(LostRange)} is the live path's half. The consumer was handed an offset
 *       the checkpoint cannot reach, and the mode's own basis has to say whether the range below it
 *       has been rebuilt. Only a mode that rebuilt it may let the checkpoint move to that offset. Its
 *       answer is an {@link Outcome} rather than a yes or no because the supervisor reads a second
 *       thing from it - whether the range has been answered at all, or has to be asked about again on
 *       the next cycle.</li>
 * </ul>
 */
public interface ContestScoreboardRecoveryStrategy {

    ContestScoreboardRecoveryMode mode();

    /**
     * Whether this mode has to run its own history recovery before the live consumer may read anything.
     *
     * <p>The question is only about a cold start, and it is about which of the two things that happen
     * then is allowed to go first. A consumer that starts at the stored checkpoint re-reads every
     * offset above it, so on a JVM whose Redis was restored from a snapshot it is a history recovery -
     * whichever mode the operator selected. For {@code stream-offset} that is not a side effect to be
     * avoided but the mode itself: the offset and the standings were written together and rolled back
     * together, so re-reading from the checkpoint is what puts back what the rollback took away.</p>
     *
     * <p>For the other two it is the wrong basis. {@code full-replay} rebuilds from MySQL and
     * {@code redis-seq} from the sequence, and a consumer that re-read the stream first would have
     * repaired the standings through a mechanism neither of them owns - after which the pass they are
     * named for runs over a scoreboard that no longer needs it, and nothing distinguishes the three
     * modes from each other. They answer true and the consumer waits on
     * {@link ContestScoreboardRecoveryCutover} until their own pass has run.</p>
     *
     * @return true when the mode's startup pass must cover the restored history before consumption
     *         begins, which is every mode except {@code stream-offset}
     */
    boolean recoversHistoryBeforeConsuming();

    /**
     * Whether a checkpoint that Redis rolled back behind what this JVM applied is repaired by
     * re-reading the stream from that checkpoint.
     *
     * <p>Only {@code stream-offset} answers true. It is the one mode whose historical basis is the
     * stored offset, so re-reading is not a substitute for its recovery - it is the recovery.</p>
     */
    boolean rewindsOnCheckpointRegression();

    /**
     * Answers how the history this mode treats as its basis affects the live range.
     *
     * <p>Idempotent, and expected to run once per lost range: a mode whose basis already covers the
     * range returns {@link Outcome#COVERED} immediately rather than rebuilding it twice. Redis-seq is
     * the live exception: a range inside this JVM's applied history can return
     * {@link Outcome#LIVE_PROGRESS}, allowing the live checkpoint to advance while repair continues
     * asynchronously without claiming that the range is rebuilt.</p>
     *
     * @return what the attempt achieved, which decides whether the live checkpoint may move and
     *         whether the range has to be asked about again
     */
    Outcome rebuildHistory(LostRange range);

    /**
     * What one attempt at {@link #rebuildHistory(LostRange)} achieved.
     *
     * <h2>Why this is not a boolean</h2>
     *
     * <p>The callers do two different things with the answer, and one boolean could not tell them
     * apart. The live path needs to know whether the range is covered or whether the selected mode
     * permits safe progress while repair continues. The supervisor additionally needs to know
     * whether the range has been <em>answered</em> - whether asking again could reach a different
     * answer - because that is what decides if the same rollback is looked at again on the next cycle.
     * Under one boolean, "another pass held the gate" and "the rebuild ran and failed" were the same
     * value, and the supervisor recorded the observed offsets as answered either way. A rollback that
     * could not be rebuilt at that moment was therefore never looked at again: with no new stream
     * delivery to re-ask, and nothing else on this interval asking, the history stayed missing.</p>
     *
     * <p>The outcomes below are separated by that question alone. {@link #COVERED} and
     * {@link #UNRECOVERABLE} are answers - one that the range is rebuilt, one that this mode cannot
     * rebuild it - so neither is repeated until the observed offsets change. {@link #BUSY_RETRY_LATER}
     * and {@link #RETRYABLE_FAILURE} are not answers at all: nothing was learned about the range, so it
     * must be asked about again on the next supervisor cycle, traffic or no traffic.</p>
     */
    enum Outcome {

        /**
         * The range is rebuilt: a reconstruction in this JVM already covered it, or one just did.
         *
         * <p>The only outcome that lets the checkpoint move above the range.</p>
         */
        COVERED,

        /**
         * The range lies inside history this JVM already applied, so the live path may keep moving
         * while this mode repairs its sequence history asynchronously.
         *
         * <p>This is deliberately not {@link #COVERED}. It permits only the live checkpoint advance;
         * it does not say that a check completed and must not advance {@code rebuiltThrough}. The
         * immediate check is one bounded attempt; the existing fixed-delay checks own retries.</p>
         */
        LIVE_PROGRESS,

        /**
         * Another pass held the gate, so this attempt never ran.
         *
         * <p>Nothing was attempted and nothing was learned. The other pass may well cover this range -
         * it is reading the same stored results - so the range is asked about again rather than
         * remembered, and the pass that held the gate is the reason not to run a second reader
         * alongside it.</p>
         */
        BUSY_RETRY_LATER,

        /**
         * The attempt ran and failed, and a later one may succeed.
         *
         * <p>A read that timed out, a database that was briefly unavailable, a write that was refused:
         * the range is exactly as recoverable as it was a moment ago, so it is asked about again.</p>
         */
        RETRYABLE_FAILURE,

        /**
         * This mode cannot rebuild the range in its current configuration.
         *
         * <p>The mode's basis cannot hold the range - offsets that were never applied, or a
         * retention-gap fallback configured as {@code none} - so repeating the attempt would reach the
         * same answer at the cost of a full pass every interval. It is remembered as answered instead,
         * which is why it has to be loud: the answer is a refusal, and the range stays missing until an
         * operator replays from MySQL or changes the mode.</p>
         *
         * <p>Remembering it means a change of configuration alone does not re-ask - the observed
         * offsets are what the memory is keyed on, and a range nobody can rebuild leaves them where
         * they are. A restart does re-ask, and the ERROR the refusal is logged at says so.</p>
         */
        UNRECOVERABLE;

        /** Whether the range is rebuilt, which is what decides if the checkpoint may move above it. */
        public boolean covers() {
            return this == COVERED;
        }

        /** Whether the live processor may anchor and apply above the observed rollback range. */
        public boolean permitsLiveProgress() {
            return this == COVERED || this == LIVE_PROGRESS;
        }

        /**
         * Whether asking again could reach a different answer, so the range must not be remembered as
         * handled.
         */
        public boolean retryable() {
            return this == BUSY_RETRY_LATER || this == RETRYABLE_FAILURE;
        }

        /** The value used in logs and as the retry metric's tag, in the same spelling as PassKind's. */
        public String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * The range whose results may be absent from the standings, stated by both of its ends.
     *
     * <p>A range is the offsets between two offsets, and a mode's basis is asked about all of them:
     * whether a completed reconstruction covered them, and whether they were ever applied here. Both
     * questions are about the range's top, so the top is a value the caller states rather than
     * something a predicate infers from the checkpoint. Inferring it was a defect twice over - a
     * reconstruction that covered an earlier, lower range was read as covering this one, and a range
     * reaching above what this JVM applied was read as being inside it because the checkpoint below
     * the range was.</p>
     *
     * <p>The bottom is not a component for the same reason: {@link #firstLostOffset()} is derived from
     * the checkpoint, because a range always begins directly above it. The two callers of this record
     * describe the same boundary - the offsets the checkpoint cannot reach - from the two things that
     * observe it, and neither has a different bottom to offer. Leaving it as a field invited a caller
     * to pass something else, which would have moved the threshold silently.</p>
     *
     * @param checkpointOffset     the offset Redis currently holds, or {@code -1} when it holds none
     * @param lastLostOffset       the highest offset whose result the standings may be missing. The
     *                             supervisor names the highest offset the rollback took away, the live
     *                             path the offset just below the delivery that jumped the checkpoint
     * @param highestAppliedOffset the highest offset a completed batch of this JVM applied. It
     *                             survives a Redis rollback in memory, so it is the one record of how
     *                             far the rollback reached back
     * @param rebuiltThrough       the highest offset a completed reconstruction in this JVM covers, or
     *                             {@code -1} when none has run
     */
    record LostRange(long checkpointOffset,
                     long lastLostOffset,
                     long highestAppliedOffset,
                     long rebuiltThrough) {

        /**
         * The first offset whose result the standings may be missing, derived from the checkpoint.
         *
         * <p>Meaningless without a checkpoint, and never asked for in that case: a scoreboard that
         * holds no checkpoint adopts the first offset it is handed instead of describing a range below
         * it.</p>
         */
        public long firstLostOffset() {
            return checkpointOffset + 1L;
        }

        /**
         * Whether a reconstruction that already ran covers the whole range, so this call must not
         * start another one.
         *
         * <p>The top of the range, not its bottom. A reconstruction is claimed to cover every offset
         * up to the watermark it recorded, and a range that reaches above that watermark is not
         * covered by it - whatever the checkpoint below says. Comparing against the checkpoint was
         * what let a rollback that reached further back than the last reconstruction be reported as
         * rebuilt without one running, leaving the results between the two to be stepped over by the
         * delivery that anchored past them.</p>
         *
         * <p>The handoff between the two callers survives: the supervisor marks the range it rebuilt
         * through at the applied watermark, and the delivery that anchors directly above it asks about
         * that offset, so the completed work is recognised and not repeated.</p>
         */
        public boolean rebuiltAlready() {
            return rebuiltThrough >= lastLostOffset;
        }

        /**
         * Whether the range is entirely inside what this JVM had already applied before the rollback.
         *
         * <p>A mode that records its progress in MySQL can treat such a range as recoverable: a
         * sequence or a timestamp reached the database only after the scoreboard issued it, so
         * anything this JVM applied has a record the mode can find. A range reaching past that point
         * was never applied at all, and no amount of replaying what is stored can reconstruct it.</p>
         *
         * <p>The top of the range again. Asked of the range rather than of the checkpoint because the
         * checkpoint being below the applied watermark says only that <em>something</em> was taken away
         * - not that everything the question is about was ever applied. A delivery above a batch that
         * failed, or above a range the broker no longer serves, can sit above the applied watermark
         * with the checkpoint below it, and a mode whose basis is a record written at apply time
         * cannot find offsets that were never applied.</p>
         */
        public boolean withinAppliedHistory() {
            return lastLostOffset <= highestAppliedOffset;
        }
    }

    /** Which reconstruction a mode runs, for the pass gate and its metric. */
    enum PassKind {

        /** Re-sending stored judgements from MySQL. */
        MYSQL_REPLAY("mysql-replay"),

        /** Looking for a reused or left-behind sequence and re-applying what it finds. */
        SEQUENCE_CHECK("sequence-check");

        private final String label;

        PassKind(String label) {
            this.label = label;
        }

        /** The value used in logs and as the pass gate's metric tag. */
        public String label() {
            return label;
        }
    }
}
