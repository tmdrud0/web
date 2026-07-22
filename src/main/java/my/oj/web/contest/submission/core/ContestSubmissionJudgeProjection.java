package my.oj.web.contest.submission.core;

import java.time.LocalDateTime;

public interface ContestSubmissionJudgeProjection {

    Long getSubmissionId();

    Long getContestId();

    Long getProblemId();

    Long getUserId();

    LocalDateTime getContestStart();

    LocalDateTime getSubmittedTime();

    String getCode();
}
