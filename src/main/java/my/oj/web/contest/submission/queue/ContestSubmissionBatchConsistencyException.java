package my.oj.web.contest.submission.queue;

import java.util.List;

public class ContestSubmissionBatchConsistencyException extends IllegalStateException {

    private final List<Long> offendingSubmissionIds;

    public ContestSubmissionBatchConsistencyException(String message) {
        this(message, List.of());
    }

    public ContestSubmissionBatchConsistencyException(String message, List<Long> offendingSubmissionIds) {
        super(message);
        this.offendingSubmissionIds = List.copyOf(offendingSubmissionIds);
    }

    /**
     * Reserved submission ids this batch could not account for. The bulk writer fails exactly these
     * requests and retries the rest, so a single bad row does not take a whole chunk down with it.
     *
     * <p>Empty when the failure cannot be attributed to individual rows — a duplicate reserved id
     * within one batch means the id generator itself is broken, and every row in the chunk is
     * suspect. Callers must fail the whole chunk in that case.
     */
    public List<Long> offendingSubmissionIds() {
        return offendingSubmissionIds;
    }
}
