package my.oj.web.contest.submission.core;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public interface ContestSubmissionStoredJudgeResultProjection {

    Long getSubmissionId();

    Long getContestId();

    Long getProblemId();

    Long getUserId();

    LocalDateTime getContestStart();

    LocalDateTime getSubmittedTime();

    SubmissionResult getResult();

    LocalDateTime getJudgedAt();
}
