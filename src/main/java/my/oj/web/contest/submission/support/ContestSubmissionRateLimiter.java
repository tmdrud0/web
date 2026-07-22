package my.oj.web.contest.submission.support;

import java.time.Duration;
import java.util.Optional;

public interface ContestSubmissionRateLimiter {

    Optional<Duration> tryAcquire(long contestId, long userId);

    void release(long contestId, long userId);
}
