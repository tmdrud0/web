package my.oj.web.problem;

import my.oj.web.api.ResourceNotFoundException;

/**
 * A submission named a problem id that does not exist.
 *
 * <p>A distinct type because the status this deserves is not the status a generic failure
 * deserves: {@code SubmissionService} raised a bare {@code IllegalStateException} here, which the
 * API could only fall through to its catch-all and answer 500 - a server error for a request the
 * client got wrong. Measured during a load run: one hand-run curl against a missing problem id was
 * recorded as a server failure alongside the real ones.
 */
public class ProblemNotFoundException extends ResourceNotFoundException {

    public ProblemNotFoundException(long problemId) {
        super("Problem not found: " + problemId);
    }
}
