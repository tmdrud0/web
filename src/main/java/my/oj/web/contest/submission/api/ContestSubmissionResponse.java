package my.oj.web.contest.submission.api;

import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.dto.SubmissionReceipt;

/**
 * What the caller gets back at accept time, which is a receipt rather than a result: judging
 * happens after the response. {@code duplicate} is the dedup store reporting that this submission
 * was already accepted, so a client retrying a timed-out request can tell that apart from a second
 * submission being queued.
 */
public record ContestSubmissionResponse(Long submissionId, SubmissionOrigin origin, boolean duplicate) {

    public static ContestSubmissionResponse from(SubmissionReceipt receipt) {
        return new ContestSubmissionResponse(receipt.submissionId(), receipt.origin(), receipt.duplicate());
    }
}
