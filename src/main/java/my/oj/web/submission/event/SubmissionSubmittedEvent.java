package my.oj.web.submission.event;

import my.oj.web.submission.SubmissionOrigin;

public record SubmissionSubmittedEvent(Long submissionId,
                                       SubmissionOrigin origin) {
}
