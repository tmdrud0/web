package my.oj.web.submission.store;

import my.oj.web.submission.SubmissionOrigin;

public record SubmissionStoreResult(Long submissionId,
                                    SubmissionOrigin origin,
                                    boolean duplicate) {
    public SubmissionStoreResult(Long submissionId, SubmissionOrigin origin) {
        this(submissionId, origin, false);
    }
}
