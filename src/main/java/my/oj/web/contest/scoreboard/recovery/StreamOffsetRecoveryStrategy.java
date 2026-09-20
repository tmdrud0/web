package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;
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
 * <p>That refusal is a {@link Outcome#RETRYABLE_FAILURE} rather than an {@code UNRECOVERABLE} one, and
 * the name is the point: this basis did not rebuild the range, but nothing has been decided about it
 * either. The rewind that owns it restarts the consumer at the checkpoint, the batch is delivered
 * again, and the question does not come back - which is a retry, not a repair this call performed.</p>
 *
 * <p>A range reaching above what this process applied is a different question and does get the
 * fallback. Those offsets were published without this process applying them - that is the only way an
 * offset sits above its own watermark - and the judge writes MySQL before it publishes, so a replay
 * does cover them. The two ends of the range are handed over as they are, so the report names the range
 * the delivery actually jumped rather than a successor offset that nothing observed. A fallback
 * configured as {@code none} refuses instead, and that refusal is the one this mode reports as
 * {@code UNRECOVERABLE}: it is a decision about the configuration, and asking again reaches it again.</p>
 */
@Slf4j
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

    /**
     * False, and this is the mode that makes the question worth asking.
     *
     * <p>Resuming at the stored checkpoint re-reads every offset above it, which is a history recovery
     * for any mode. Here it is the intended one: the checkpoint and the standings were written in the
     * same Lua invocation and rolled back together, so the offsets the rollback took away are still in
     * retention and reading them again is what puts the results back. Waiting for a pass that does not
     * exist would leave the consumer held for good.</p>
     */
    @Override
    public boolean recoversHistoryBeforeConsuming() {
        return false;
    }

    @Override
    public boolean rewindsOnCheckpointRegression() {
        return true;
    }

    @Override
    public Outcome rebuildHistory(LostRange range) {
        if (range.withinAppliedHistory()) {
            // Everything in the range was applied here before the rollback, so it is the supervisor's
            // rewind that repairs it and this call has nothing to rebuild. Retryable, not
            // unrecoverable: the range is not missing anything a later attempt could not reach, and
            // nothing here may be remembered as answered - the supervisor's pass is the answer, and it
            // has not run yet.
            return Outcome.RETRYABLE_FAILURE;
        }
        try {
            return gate.tryRun(PassKind.MYSQL_REPLAY, () -> recoveryService.recoverRetentionGap(
                    range.checkpointOffset(),
                    range.lastLostOffset()
            ) ? Outcome.COVERED : Outcome.UNRECOVERABLE).orElse(Outcome.BUSY_RETRY_LATER);
        } catch (RuntimeException failure) {
            log.error("The MySQL fallback could not replay the offsets {} to {} the stream no longer "
                            + "serves; the batch is left unapplied and the replay is retried on the next "
                            + "supervisor cycle",
                    range.firstLostOffset(), range.lastLostOffset(), failure);
            return Outcome.RETRYABLE_FAILURE;
        }
    }
}
