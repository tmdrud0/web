package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.contest.scoreboard.ContestScoreboardService;

public class DirectContestScoreboardOutboxApplier implements ContestScoreboardOutboxApplier {

    private final ContestScoreboardService scoreboardService;

    public DirectContestScoreboardOutboxApplier(ContestScoreboardService scoreboardService) {
        this.scoreboardService = scoreboardService;
    }

    @Override
    public Long apply(Long eventId, ContestScoreboardOutboxPayload payload) {
        scoreboardService.recordJudgement(
                eventId,
                payload.contestId(),
                payload.problemId(),
                payload.userId(),
                payload.contestStart(),
                payload.submittedTime(),
                payload.result()
        );
        return null;
    }

    @Override
    public long currentSequence() {
        return Long.MAX_VALUE;
    }
}
