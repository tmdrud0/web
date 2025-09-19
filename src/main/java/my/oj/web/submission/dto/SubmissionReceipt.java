package my.oj.web.submission.dto;

import my.oj.web.submission.SubmissionOrigin;

public record SubmissionReceipt(Long submissionId,
                                SubmissionOrigin origin,
                                boolean duplicate) {

    public SubmissionReceipt(Long submissionId, SubmissionOrigin origin) {
        this(submissionId, origin, false);
    }

    public boolean isContest() {
        return origin == SubmissionOrigin.CONTEST;
    }

    public boolean isDuplicate() {
        return duplicate;
    }
}
