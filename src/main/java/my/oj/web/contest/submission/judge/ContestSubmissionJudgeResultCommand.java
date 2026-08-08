package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionStoredJudgeResultProjection;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public record ContestSubmissionJudgeResultCommand(
        Long submissionId,
        Long contestId,
        Long problemId,
        Long userId,
        LocalDateTime contestStart,
        LocalDateTime submittedTime,
        SubmissionResult result,
        LocalDateTime judgedAt
) {

    public static ContestSubmissionJudgeResultCommand from(ContestSubmissionJudgeProjection submission,
                                                            SubmissionResult result,
                                                            LocalDateTime judgedAt) {
        return new ContestSubmissionJudgeResultCommand(
                submission.getSubmissionId(),
                submission.getContestId(),
                submission.getProblemId(),
                submission.getUserId(),
                submission.getContestStart(),
                submission.getSubmittedTime(),
                result,
                judgedAt
        );
    }

    public static ContestSubmissionJudgeResultCommand from(
            ContestSubmissionStoredJudgeResultProjection storedResult
    ) {
        return new ContestSubmissionJudgeResultCommand(
                storedResult.getSubmissionId(),
                storedResult.getContestId(),
                storedResult.getProblemId(),
                storedResult.getUserId(),
                storedResult.getContestStart(),
                storedResult.getSubmittedTime(),
                storedResult.getResult(),
                storedResult.getJudgedAt()
        );
    }
}
