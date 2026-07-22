package my.oj.web.contest.submission.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisContestSubmissionRateLimiterTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisContestSubmissionRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        rateLimiter = new RedisContestSubmissionRateLimiter(redisTemplate, 3000L);
    }

    @Test
    void tryAcquire_returnsEmpty_whenRedisLockAcquired() {
        when(valueOperations.setIfAbsent("contest:submission:rate-limit:7:11", "1", Duration.ofMillis(3000)))
                .thenReturn(Boolean.TRUE);

        Optional<Duration> result = rateLimiter.tryAcquire(7L, 11L);

        assertThat(result).isEmpty();
    }

    @Test
    void tryAcquire_returnsRemainingTtl_whenAlreadyLocked() {
        when(valueOperations.setIfAbsent("contest:submission:rate-limit:7:11", "1", Duration.ofMillis(3000)))
                .thenReturn(Boolean.FALSE);
        when(redisTemplate.getExpire("contest:submission:rate-limit:7:11", TimeUnit.MILLISECONDS))
                .thenReturn(1800L);

        Optional<Duration> result = rateLimiter.tryAcquire(7L, 11L);

        assertThat(result).contains(Duration.ofMillis(1800L));
    }

    @Test
    void release_deletesRedisKey() {
        rateLimiter.release(7L, 11L);

        verify(redisTemplate).delete("contest:submission:rate-limit:7:11");
    }
}
