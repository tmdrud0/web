package my.oj.web.contest.submission.support;

import java.time.Duration;

public class ContestSubmissionRateLimitExceededException extends IllegalArgumentException {

    public ContestSubmissionRateLimitExceededException(Duration retryAfter) {
        super(buildMessage(retryAfter));
    }

    private static String buildMessage(Duration retryAfter) {
        long waitSeconds = Math.max(1L, (long) Math.ceil(retryAfter.toMillis() / 1000.0));
        String unit = waitSeconds == 1L ? "second" : "seconds";
        return "Contest submissions are too frequent. Please wait " + waitSeconds + " " + unit + " and try again.";
    }
}
