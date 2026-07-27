package my.oj.web.contest.submission.support;

/**
 * Signals that the bounded contest-submission writer cannot accept more work.
 */
public class ContestSubmissionOverloadedException extends IllegalStateException {

    public static final long DEFAULT_RETRY_AFTER_SECONDS = 1L;

    public ContestSubmissionOverloadedException() {
        super("Contest submissions are temporarily busy. Please try again in 1 second.");
    }

    public long retryAfterSeconds() {
        return DEFAULT_RETRY_AFTER_SECONDS;
    }
}
