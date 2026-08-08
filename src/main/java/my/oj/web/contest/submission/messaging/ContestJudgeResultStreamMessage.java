package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultCommand;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public record ContestJudgeResultStreamMessage(
        int schemaVersion,
        Long submissionId,
        Long contestId,
        Long problemId,
        Long userId,
        LocalDateTime contestStart,
        LocalDateTime submittedTime,
        LocalDateTime judgedAt,
        SubmissionResult result
) {

    static final int CURRENT_SCHEMA_VERSION = 1;

    static ContestJudgeResultStreamMessage from(ContestSubmissionJudgeResultCommand command) {
        return new ContestJudgeResultStreamMessage(
                CURRENT_SCHEMA_VERSION,
                command.submissionId(),
                command.contestId(),
                command.problemId(),
                command.userId(),
                command.contestStart(),
                command.submittedTime(),
                command.judgedAt(),
                command.result()
        );
    }
}
