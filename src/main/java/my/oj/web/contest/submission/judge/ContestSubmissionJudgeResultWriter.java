package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public interface ContestSubmissionJudgeResultWriter {

    void persist(ContestSubmissionJudgeProjection submission,
                 SubmissionResult result,
                 LocalDateTime judgedAt);

    /**
     * Persists a result together with the instant the worker picked the submission up. A writer that
     * does not record the start falls back to the three-argument form.
     */
    default void persist(ContestSubmissionJudgeProjection submission,
                         SubmissionResult result,
                         LocalDateTime judgeStartedAt,
                         LocalDateTime judgedAt) {
        persist(submission, result, judgedAt);
    }

    void republish(ContestSubmissionJudgeResultCommand storedResult);
}
