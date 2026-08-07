package my.oj.web.contest.submission.support;

import java.time.Duration;

/**
 * The per-user submission cooldown refused this submission.
 *
 * <p>Carries how long the caller has to wait, the same way
 * {@link ContestSubmissionOverloadedException} does. These are the two ways the system declines
 * work it could otherwise do, and both are answers a client can act on rather than faults - one
 * because the writer is saturated, this one because the user is ahead of their own cooldown.
 *
 * <p>Still an {@link IllegalArgumentException}: the page handler catches that to turn a cooldown
 * into a flash message and a redirect, and changing the supertype would turn it into a 500 there.
 * The JSON API selects on this type directly, so the supertype no longer decides its status.
 */
public class ContestSubmissionRateLimitExceededException extends IllegalArgumentException {

    private final long retryAfterSeconds;

    public ContestSubmissionRateLimitExceededException(Duration retryAfter) {
        super(buildMessage(retryAfter));
        this.retryAfterSeconds = waitSeconds(retryAfter);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String buildMessage(Duration retryAfter) {
        long waitSeconds = waitSeconds(retryAfter);
        String unit = waitSeconds == 1L ? "second" : "seconds";
        return "Contest submissions are too frequent. Please wait " + waitSeconds + " " + unit + " and try again.";
    }

    private static long waitSeconds(Duration retryAfter) {
        return Math.max(1L, (long) Math.ceil(retryAfter.toMillis() / 1000.0));
    }
}
