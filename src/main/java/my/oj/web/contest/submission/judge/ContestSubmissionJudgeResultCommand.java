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
        LocalDateTime judgedAt,
        LocalDateTime judgeStartedAt
) {

    /** A command with no recorded judge start: a republished stored result, or a caller that did not time it. */
    public ContestSubmissionJudgeResultCommand(Long submissionId,
                                               Long contestId,
                                               Long problemId,
                                               Long userId,
                                               LocalDateTime contestStart,
                                               LocalDateTime submittedTime,
                                               SubmissionResult result,
                                               LocalDateTime judgedAt) {
        this(submissionId, contestId, problemId, userId, contestStart, submittedTime, result, judgedAt, null);
    }

    public static ContestSubmissionJudgeResultCommand from(ContestSubmissionJudgeProjection submission,
                                                            SubmissionResult result,
                                                            LocalDateTime judgedAt) {
        return from(submission, result, null, judgedAt);
    }

    public static ContestSubmissionJudgeResultCommand from(ContestSubmissionJudgeProjection submission,
                                                            SubmissionResult result,
                                                            LocalDateTime judgeStartedAt,
                                                            LocalDateTime judgedAt) {
        return new ContestSubmissionJudgeResultCommand(
                submission.getSubmissionId(),
                submission.getContestId(),
                submission.getProblemId(),
                submission.getUserId(),
                submission.getContestStart(),
                submission.getSubmittedTime(),
                result,
                judgedAt,
                judgeStartedAt
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
