package my.oj.web.contest.submission.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the dedup registry from {@code contest.submission.dedup.store}.
 *
 * <p>The {@code memory} branch lives on {@link InMemoryContestSubmissionDuplicateRegistry}
 * itself. Every branch states its value explicitly, so an unrecognised setting leaves no
 * bean and the application fails to start rather than quietly dropping duplicate
 * detection.
 */
@Configuration
public class ContestSubmissionDuplicateRegistryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "contest.submission.dedup", name = "store", havingValue = "redis")
    ContestSubmissionDuplicateRegistry redisContestSubmissionDuplicateRegistry(StringRedisTemplate redisTemplate) {
        return new RedisContestSubmissionDuplicateRegistry(redisTemplate);
    }

}
