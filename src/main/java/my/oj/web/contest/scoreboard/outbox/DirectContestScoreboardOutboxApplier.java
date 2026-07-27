package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

public class DirectContestScoreboardOutboxApplier implements ContestScoreboardOutboxApplier {

    private final ContestScoreboardService scoreboardService;

    public DirectContestScoreboardOutboxApplier(ContestScoreboardService scoreboardService) {
        this.scoreboardService = scoreboardService;
    }

    @Override
    public Long apply(Long eventId, ContestScoreboardUpdate update) {
        scoreboardService.recordJudgement(
                eventId,
                update.contestId(),
                update.problemId(),
                update.userId(),
                update.contestStart(),
                update.submittedTime(),
                update.result()
        );
        return null;
    }

    @Override
    public long currentSequence() {
        return Long.MAX_VALUE;
    }
}
