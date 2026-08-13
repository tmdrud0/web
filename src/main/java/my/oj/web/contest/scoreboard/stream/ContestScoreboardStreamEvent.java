package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;

record ContestScoreboardStreamEvent(long offset, ContestJudgeResultStreamMessage message) {

    ContestScoreboardUpdate update() {
        return new ContestScoreboardUpdate(
                message.submissionId(),
                message.contestId(),
                message.problemId(),
                message.userId(),
                message.contestStart(),
                message.submittedTime(),
                message.result(),
                message.judgedAt()
        );
    }
}
