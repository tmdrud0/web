package my.oj.web.contest.scoreboard.recovery;

/**
 * {@code full-replay}: MySQL holds every stored judgement, so MySQL is the history.
 *
 * <p>The mode never rewinds the consumer. A rollback leaves the checkpoint behind, but the results
 * the rollback took away are still in {@code contest_submission_result} with a
 * {@code scoreboard_applied_at} to say whether the standings ever saw them, so re-sending them is
 * what repairs the standings - and the stream offset plays no part in it.</p>
 *
 * <p>Leaving the consumer alone is not a detail. The judge writes a result to MySQL before it
 * publishes to the stream, so a replay started after the rollback was detected is guaranteed to see
 * every result published before it, and the consumer that was never stopped goes on applying
 * everything published after it. That is the whole argument that no result published during the
 * recovery is missed, and stopping the consumer would throw it away for nothing.</p>
 *
 * <p>A range is therefore always recoverable, whatever the checkpoint says: the only thing that could
 * make it unrecoverable is a result MySQL does not have, and a result reaches the stream only after
 * it reached MySQL.</p>
 */
class FullReplayRecoveryStrategy implements ContestScoreboardRecoveryStrategy {

    private final ContestScoreboardFullReplayService replayService;
    private final ContestScoreboardRecoveryPassGate gate;

    FullReplayRecoveryStrategy(ContestScoreboardFullReplayService replayService,
                               ContestScoreboardRecoveryPassGate gate) {
        this.replayService = replayService;
        this.gate = gate;
    }

    @Override
    public ContestScoreboardRecoveryMode mode() {
        return ContestScoreboardRecoveryMode.FULL_REPLAY;
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
        return gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            replayService.replayAllContests();
            return Boolean.TRUE;
        }).orElse(Boolean.FALSE);
    }
}
