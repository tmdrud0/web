package my.oj.web.submission.event;

import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public record SubmissionResultEvent(Long submissionId,
                                    SubmissionOrigin origin,
                                    SubmissionResult result,
                                    LocalDateTime judgedAt) {
}
