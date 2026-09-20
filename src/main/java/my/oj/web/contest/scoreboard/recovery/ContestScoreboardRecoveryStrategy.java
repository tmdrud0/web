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
     * @return whether the range below {@link LostRange#firstLostOffset()} is rebuilt, which is what
     *         decides if the checkpoint may move to the offset that follows it
     */
    boolean rebuildHistory(LostRange range);

    /**
     * The range whose results may be absent from the standings.
     *
     * @param checkpointOffset     the offset Redis currently holds, or {@code -1} when it holds none
     * @param firstLostOffset      the first offset whose result the standings may be missing
     * @param highestAppliedOffset the highest offset this JVM applied. It survives a Redis rollback in
     *                             memory, so it is the one record of how far the rollback reached back
     * @param rebuiltThrough       the highest offset a completed reconstruction in this JVM already
     *                             covers, or {@code -1} when none has run
     */
    record LostRange(long checkpointOffset,
                     long firstLostOffset,
                     long highestAppliedOffset,
                     long rebuiltThrough) {

        /**
         * Whether a reconstruction that already ran covers the whole range, so this call must not
         * start another one.
         *
         * <p>{@code firstLostOffset - 1} rather than {@code firstLostOffset}: a reconstruction is
         * claimed to cover the range <em>below</em> the first lost offset. The two callers describe
         * the same gap from different sides - the supervisor names the checkpoint it rolled back to
         * and marks the offset it rebuilt through, the live path names the first offset it was handed
         * and asks about everything before it - so requiring the marked offset to reach the first
         * <em>lost</em> one would make each caller fail to recognise the other's completed work and
         * rebuild it twice.</p>
         */
        public boolean rebuiltAlready() {
            return rebuiltThrough >= firstLostOffset - 1L;
        }

        /**
         * Whether the range is entirely inside what this JVM had already applied before the rollback.
         *
         * <p>A mode that records its progress in MySQL can treat such a range as recoverable: a
         * sequence or a timestamp reached the database only after the scoreboard issued it, so
         * anything this JVM applied has a record the mode can find. A range reaching past that point
         * was never applied at all, and no amount of replaying what is stored can reconstruct it.</p>
         *
         * <p>Judged by the checkpoint rather than by {@code firstLostOffset}. The two callers place
         * the first lost offset differently - the supervisor at {@code checkpointOffset + 1}, the live
         * path at the offset it was handed - and only the live path's one sits above the highest
         * applied offset in the ordinary case, so a test on {@code firstLostOffset} would answer false
         * for the supervisor's range and refuse a recovery that is in fact available. What the
         * question really asks is whether the checkpoint is below what this JVM applied: only then did
         * the rollback take away results the mode has a record of.</p>
         */
        public boolean withinAppliedHistory() {
            return checkpointOffset < highestAppliedOffset;
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
