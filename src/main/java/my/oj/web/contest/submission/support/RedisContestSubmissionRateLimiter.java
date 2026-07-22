package my.oj.web.contest.submission.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "contest.submission.rate-limit", name = "store", havingValue = "redis")
public class RedisContestSubmissionRateLimiter implements ContestSubmissionRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final Duration cooldown;

    public RedisContestSubmissionRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${contest.submission.rate-limit.cooldown-millis:3000}") long cooldownMillis
    ) {
        this.redisTemplate = redisTemplate;
        this.cooldown = Duration.ofMillis(Math.max(1L, cooldownMillis));
    }

    @Override
    public Optional<Duration> tryAcquire(long contestId, long userId) {
        String key = key(contestId, userId);
        boolean acquired = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key, "1", cooldown)
        );
        if (acquired) {
            return Optional.empty();
        }

        Long remainingMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        if (remainingMillis == null || remainingMillis < 1L) {
            return Optional.of(cooldown);
        }
        return Optional.of(Duration.ofMillis(remainingMillis));
    }

    @Override
    public void release(long contestId, long userId) {
        redisTemplate.delete(key(contestId, userId));
    }

    private String key(long contestId, long userId) {
        return "contest:submission:rate-limit:" + contestId + ":" + userId;
    }
}
