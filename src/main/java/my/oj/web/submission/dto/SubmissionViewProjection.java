package my.oj.web.submission.dto;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public interface SubmissionViewProjection {
    Long getId();
    Long getProblemId();
    String getProblemName();
    Long getUserId();
    String getUsername();
    SubmissionResult getResult();
    String getCode();
    LocalDateTime getSubmittedTime();
}
