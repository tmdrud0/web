package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public interface ContestSubmissionJudgeResultWriter {

    void persist(ContestSubmissionJudgeProjection submission,
                 SubmissionResult result,
                 LocalDateTime judgedAt);

    void republish(ContestSubmissionJudgeResultCommand storedResult);
}
