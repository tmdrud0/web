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

    @Override
    public boolean rewindsOnCheckpointRegression() {
        return false;
    }

    @Override
    public boolean rebuildHistory(LostRange range) {
        if (range.rebuiltAlready()) {
            return true;
        }
        if (!range.withinAppliedHistory()) {
            log.error("Scoreboard stream offsets below {} include ones this process never applied, so the "
                            + "sequence check cannot find them: their results have no scoreboard_applied_seq "
                            + "to compare against the allocator, and neither the duplicate scan nor the "
                            + "sequenced-tail walk can see a result that was never applied. The batch is left "
                            + "unapplied rather than moving the checkpoint past results the standings never "
                            + "saw. Replay from MySQL, or switch to the full-replay mode, to rebuild them.",
                    range.firstLostOffset());
            return false;
        }
        return gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
            ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report = recoveryService.check();
            // A pass that spent every round, or every window in a round, has not seen the whole set.
            // Reporting the range as rebuilt on either would let the checkpoint move over candidates
            // the check never reached.
            return !report.unresolved() && !report.saturated();
        }).orElse(Boolean.FALSE);
    }
}
