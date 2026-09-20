package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.stream.ContestScoreboardStreamRecoveryService;

/**
 * {@code stream-offset}: the offset stored beside the scoreboard is the history.
 *
 * <p>This is the one mode that repairs a rollback by reading the stream again. The checkpoint and the
 * standings were written in the same Lua invocation and rolled back together, so everything the
 * rollback took away is still in the broker's retention and re-reading from the checkpoint is exactly
 * what puts it back. The re-read is also why nothing is missed while it runs: the consumer is
 * restarted at the checkpoint, so it resumes where the rollback left the scoreboard rather than where
 * it had got to.</p>
 *
 * <p>A range the broker no longer retains cannot be read back, and the mode's answer is the
 * configured fallback - a full replay from MySQL, or nothing at all. {@code none} leaves the batch
 * unapplied rather than moving the checkpoint past results the standings never saw.</p>
 *
 * <p>A rollback is not this service's question, and asking it here would be wrong twice over. The
 * offset is retained; it is the scoreboard that moved. Replaying MySQL would answer a question about
 * the retention window that was never asked, and the mode's actual answer - restart the consumer at
 * the checkpoint and read the results again - is a decision about the running consumer, which the
 * live path does not own. So the live path refuses in that case and the supervisor's pass rewinds,
 * which is the same recovery reached by the thing that can carry it out.</p>
 *
 * <p>A range reaching above what this process applied is a different question and does get the
 * fallback. Those offsets were published without this process applying them - that is the only way an
 * offset sits above its own watermark - and the judge writes MySQL before it publishes, so a replay
 * does cover them. The two ends of the range are handed over as they are, so the report names the range
 * the delivery actually jumped rather than a successor offset that nothing observed.</p>
 */
class StreamOffsetRecoveryStrategy implements ContestScoreboardRecoveryStrategy {

    private final ContestScoreboardStreamRecoveryService recoveryService;
    private final ContestScoreboardRecoveryPassGate gate;

    StreamOffsetRecoveryStrategy(ContestScoreboardStreamRecoveryService recoveryService,
                                 ContestScoreboardRecoveryPassGate gate) {
        this.recoveryService = recoveryService;
        this.gate = gate;
    }

    @Override
    public ContestScoreboardRecoveryMode mode() {
        return ContestScoreboardRecoveryMode.STREAM_OFFSET;
    }

    @Override
    public boolean rewindsOnCheckpointRegression() {
        return true;
    }

    @Override
    public boolean rebuildHistory(LostRange range) {
        if (range.withinAppliedHistory()) {
            return false;
        }
        return gate.tryRun(PassKind.MYSQL_REPLAY, () -> recoveryService.recoverRetentionGap(
                range.checkpointOffset(),
                range.lastLostOffset()
        )).orElse(Boolean.FALSE);
    }
}
