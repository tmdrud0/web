package my.oj.web.contest.scoreboard.recovery;

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
 *   <li>{@link #rewindsOnCheckpointRegression()} is the supervisor's half. The checkpoint moved
 *       backwards, and only {@code stream-offset} answers it by re-reading the stream. The other two
 *       answer false and keep the consumer where it is, because their history basis is MySQL or the
 *       sequence, not the stream position - and a consumer that is never stopped cannot miss a result
 *       published while the rebuild runs.</li>
 *   <li>{@link #rebuildHistory(LostRange)} is the live path's half. The consumer was handed an offset
 *       the checkpoint cannot reach, and the mode's own basis has to say whether the range below it
 *       has been rebuilt. Only a mode that rebuilt it may let the checkpoint move to that offset.</li>
 * </ul>
 */
public interface ContestScoreboardRecoveryStrategy {

    ContestScoreboardRecoveryMode mode();

    /**
     * Whether a checkpoint that Redis rolled back behind what this JVM applied is repaired by
     * re-reading the stream from that checkpoint.
     *
     * <p>Only {@code stream-offset} answers true. It is the one mode whose historical basis is the
     * stored offset, so re-reading is not a substitute for its recovery - it is the recovery.</p>
     */
    boolean rewindsOnCheckpointRegression();

    /**
     * Rebuilds the history this mode treats as its basis.
     *
     * <p>Idempotent, and expected to run once per lost range: a mode whose basis already covers the
     * range returns immediately rather than rebuilding it twice.</p>
     *
     * @return whether the range is rebuilt, which is what decides if the checkpoint may move to the
     *         offset above it
     */
    boolean rebuildHistory(LostRange range);

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
