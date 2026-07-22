package my.oj.web.contest.submission.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "contest.submission.rate-limit", name = "store", havingValue = "none")
public class NoopContestSubmissionRateLimiter implements ContestSubmissionRateLimiter {

    @Override
    public Optional<Duration> tryAcquire(long contestId, long userId) {
        return Optional.empty();
    }

    @Override
    public void release(long contestId, long userId) {
        // no-op
    }
}
