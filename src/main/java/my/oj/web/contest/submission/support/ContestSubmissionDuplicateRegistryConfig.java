package my.oj.web.contest.submission.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ContestSubmissionDuplicateRegistryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "contest.submission.dedup", name = "store", havingValue = "redis")
    ContestSubmissionDuplicateRegistry redisContestSubmissionDuplicateRegistry(StringRedisTemplate redisTemplate) {
        return new RedisContestSubmissionDuplicateRegistry(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(ContestSubmissionDuplicateRegistry.class)
    ContestSubmissionDuplicateRegistry noopContestSubmissionDuplicateRegistry() {
        return new NoopContestSubmissionDuplicateRegistry();
    }
}
