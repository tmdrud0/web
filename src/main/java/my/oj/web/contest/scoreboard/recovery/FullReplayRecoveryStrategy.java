package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;

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
 * it reached MySQL. Which is also why the only refusal this mode can produce is the gate's: a replay
 * that threw is retried rather than remembered, because the range it did not cover is untouched by
 * the failure.</p>
 */
@Slf4j
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

    /**
     * True: the consumer waits for the startup replay.
     *
     * <p>Not because a result would be lost otherwise - the paragraph above is about a rollback
     * observed on a running JVM, and it still holds there: this mode never stops the consumer once it
     * is consuming. A cold start is a different question. The consumer's start sits at the end of the
     * context refresh and this mode's replay is an {@code ApplicationRunner}, so without a boundary the
     * consumer would begin re-reading the stream from the stored checkpoint first, and the history the
     * restored scoreboard was missing would be put back through the stream. This mode's basis would
     * then be replaying MySQL over a scoreboard that no longer needed it, and nothing in the log would
     * say the recovery was not the one the operator selected.</p>
     *
     * <p>Waiting costs nothing that is not recoverable. The checkpoint does not move while the replay
     * runs - a rebuild request carries no stream offset - so the consumer resumes at exactly the
     * checkpoint it would have used, and everything published during the hold is above it.</p>
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
        try {
            return gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
                replayService.replayAllContests();
                return Outcome.COVERED;
            }).orElse(Outcome.BUSY_RETRY_LATER);
        } catch (RuntimeException failure) {
            // Answered rather than thrown: the range is exactly as recoverable as it was before the
            // attempt, and an exception that reached the caller would leave the supervisor with no
            // way to say so - its own catch records nothing, and the rollback would be forgotten.
            log.error("The MySQL basis could not replay the scoreboard history the rollback took away "
                    + "between offsets {} and {}; the replay is retried on the next supervisor cycle",
                    range.firstLostOffset(), range.lastLostOffset(), failure);
            return Outcome.RETRYABLE_FAILURE;
        }
    }
}
