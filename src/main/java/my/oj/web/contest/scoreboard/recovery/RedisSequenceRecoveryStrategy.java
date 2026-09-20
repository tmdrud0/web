package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code redis-seq}: the sequence Redis issued is the history.
 *
 * <p>The allocator and the sequence mapping live in the same Redis as the standings and roll back
 * with them, while {@code scoreboard_applied_seq} in MySQL does not. So after a rollback the stored
 * sequences sit above the rewound allocator, and that gap is the lost tail - which the mode's check
 * finds and replays without ever reading the stream.</p>
 *
 * <h2>What this mode can and cannot rebuild</h2>
 *
 * <p>A range that lies inside what this JVM had already applied is recoverable: every result in it
 * reached the scoreboard, and a sequence reaches MySQL only after the scoreboard issued it, so the
 * record the check compares against is there.</p>
 *
 * <p>A range reaching past that point is not. Those results were never applied at all, so no sequence
 * was ever issued for them and {@code scoreboard_applied_seq} is null on their rows. The check walks
 * the sequenced tail, so it cannot see them; the duplicate scan groups by sequence, so it cannot
 * either. This mode has no way to learn that they exist.</p>
 *
 * <p>That is why the answer here is a refusal rather than a replay. Reporting the range as rebuilt
 * would let the checkpoint move past results the standings never saw, which is precisely the loss the
 * checkpoint exists to prevent - so the batch is failed and the state is left loud. An operator who
 * wants those results has to replay from MySQL, which is what {@code full-replay} and the
 * {@code stream-offset} retention-gap fallback are for.</p>
 */
@Slf4j
class RedisSequenceRecoveryStrategy implements ContestScoreboardRecoveryStrategy {

    private final ContestScoreboardRedisSequenceRecoveryService recoveryService;
    private final ContestScoreboardRecoveryPassGate gate;

    RedisSequenceRecoveryStrategy(ContestScoreboardRedisSequenceRecoveryService recoveryService,
                                  ContestScoreboardRecoveryPassGate gate) {
        this.recoveryService = recoveryService;
        this.gate = gate;
    }

    @Override
    public ContestScoreboardRecoveryMode mode() {
        return ContestScoreboardRecoveryMode.REDIS_SEQ;
    }

    /**
     * True: the consumer waits for the startup check.
     *
     * <p>This mode's check judges every stored sequence against the allocator it read, and the contract
     * that makes that reading correct is that every database read comes before the allocator read. A
     * consumer applying results while the check runs would be issuing sequences into the middle of that
     * window - the one thing the mode's own check cannot survive, since each side would judge the
     * other's in-flight results as lost.</p>
     *
     * <p>The cold start is also where the mode's isolation is easiest to lose. A consumer that started
     * first would re-read the stream from the stored checkpoint and put the restored history back
     * through the stream, after which the check would find nothing to repair and the mode would look
     * like it had recovered a scoreboard it never touched.</p>
     */
    @Override
    public boolean recoversHistoryBeforeConsuming() {
        return true;
    }

    @Override
    public boolean rewindsOnCheckpointRegression() {
        return false;
    }

    @Override
    public Outcome rebuildHistory(LostRange range) {
        if (range.rebuiltAlready()) {
            return Outcome.COVERED;
        }
        if (!range.withinAppliedHistory()) {
            log.error("Scoreboard stream offsets below {} include ones this process never applied, so the "
                            + "sequence check cannot find them: their results have no scoreboard_applied_seq "
                            + "to compare against the allocator, and neither the duplicate scan nor the "
                            + "sequenced-tail walk can see a result that was never applied. The batch is left "
                            + "unapplied rather than moving the checkpoint past results the standings never "
                            + "saw, and this range is not asked about again until the observed offsets "
                            + "change. Replay from MySQL to rebuild them; this refusal is remembered in this "
                            + "JVM only, so a restart re-asks, and a restart is also what changing the mode "
                            + "to full-replay takes - that mode's own pass is what would rebuild them.",
                    range.firstLostOffset());
            // Unrecoverable, and the only refusal in this mode that is: it is a property of what the
            // basis records, so a second attempt reaches the same answer. Remembering it is what keeps
            // a range nobody can rebuild from becoming a sequence check per supervisor cycle.
            return Outcome.UNRECOVERABLE;
        }
        try {
            return gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
                ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = recoveryService.check();
                // A pass that spent every round, or every window in a round, has not seen the whole
                // set. Reporting the range as rebuilt on either would let the checkpoint move over
                // candidates the check never reached.
                if (report.coveredTheWholeSet()) {
                    return Outcome.COVERED;
                }
                if (report.unresolved()) {
                    // Rounds were spent and results are still to be replayed: replaying a result the
                    // scoreboard already applied cannot take the sequence back off it, so the next
                    // round finds the same group. Another round is not the repair.
                    return Outcome.UNRECOVERABLE;
                }
                // The window budget ran out before the tail was walked to its end. Rounds replay what
                // they find, so the next pass has less to look at - this one is worth taking again.
                return Outcome.RETRYABLE_FAILURE;
            }).orElse(Outcome.BUSY_RETRY_LATER);
        } catch (RuntimeException failure) {
            log.error("The sequence check could not rebuild the scoreboard history the rollback took away "
                            + "between offsets {} and {}; the check is retried on the next supervisor cycle",
                    range.firstLostOffset(), range.lastLostOffset(), failure);
            return Outcome.RETRYABLE_FAILURE;
        }
    }
}
