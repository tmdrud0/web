package my.oj.web.problem;

/**
 * A submission named a problem id that does not exist.
 *
 * <p>A distinct type because the status this deserves is not the status a generic failure
 * deserves: {@code SubmissionService} raised a bare {@code IllegalStateException} here, which the
 * JSON API could only fall through to its catch-all and answer 500 - a server error for a request
 * the client got wrong. Measured during a load run: one hand-run curl against a missing problem id
 * was recorded as a server failure alongside the real ones.
 *
 * <p>Still an {@link IllegalStateException} so that the page handler, which does not catch it and
 * lets it become a 500, behaves exactly as it did. Narrowing that is a separate change to the page
 * error path.
 */
public class ProblemNotFoundException extends IllegalStateException {

    public ProblemNotFoundException(long problemId) {
        super("Problem not found: " + problemId);
    }
}
