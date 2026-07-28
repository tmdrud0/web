package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

/**
 * Issues real sequences rather than a placeholder, so outbox recovery behaves the same here as
 * it does against Redis and can be exercised without a container.
 */
public class InMemoryContestScoreboardApplier implements ContestScoreboardApplier {

    private final InMemoryContestScoreboard scoreboard;

    public InMemoryContestScoreboardApplier(InMemoryContestScoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }

    @Override
    public Long apply(Long eventId, ContestScoreboardUpdate update) {
        validate(eventId, update);
        return scoreboard.apply(update);
    }

    @Override
    public long currentSequence() {
        return scoreboard.currentSequence();
    }

    @Override
    public void reset(long contestId) {
        scoreboard.reset(contestId);
    }

    private static void validate(Long eventId, ContestScoreboardUpdate update) {
        if (eventId == null
                || update == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            throw new IllegalArgumentException("Scoreboard outbox event and update fields are required");
        }
    }
}
