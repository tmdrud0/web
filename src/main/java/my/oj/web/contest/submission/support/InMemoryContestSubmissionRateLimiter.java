package my.oj.web.contest.submission.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(prefix = "contest.submission.rate-limit", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryContestSubmissionRateLimiter implements ContestSubmissionRateLimiter {

    private final ConcurrentMap<RateLimitKey, Long> nextAllowedAtMillis = new ConcurrentHashMap<>();
    private final long cooldownMillis;

    public InMemoryContestSubmissionRateLimiter(
            @Value("${contest.submission.rate-limit.cooldown-millis:3000}") long cooldownMillis
    ) {
        this.cooldownMillis = Math.max(1L, cooldownMillis);
    }

    @Override
    public Optional<Duration> tryAcquire(long contestId, long userId) {
        long now = System.currentTimeMillis();
        RateLimitKey key = new RateLimitKey(contestId, userId);
        long[] retryAfterMillis = new long[1];

        nextAllowedAtMillis.compute(key, (ignored, existing) -> {
            if (existing != null && existing > now) {
                retryAfterMillis[0] = existing - now;
                return existing;
            }
            return now + cooldownMillis;
        });

        if (retryAfterMillis[0] > 0L) {
            return Optional.of(Duration.ofMillis(retryAfterMillis[0]));
        }
        return Optional.empty();
    }

    @Override
    public void release(long contestId, long userId) {
        nextAllowedAtMillis.remove(new RateLimitKey(contestId, userId));
    }

    @Scheduled(fixedDelayString = "${contest.submission.rate-limit.memory.cleanup-interval-millis:60000}")
    public void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        nextAllowedAtMillis.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private record RateLimitKey(long contestId, long userId) {
    }
}
