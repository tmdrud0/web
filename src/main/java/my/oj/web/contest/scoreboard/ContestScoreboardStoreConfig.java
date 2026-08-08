package my.oj.web.contest.scoreboard;

import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboard;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardApplier;
import my.oj.web.contest.scoreboard.redis.ContestRedisKeyValueClient;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplier;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplyMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the scoreboard write path from {@code contest.scoreboard.store}, matching whichever
 * reader that same property selected.
 *
 * <p>Both branches state their value explicitly, so an unrecognised setting leaves no bean
 * and the application fails to start rather than silently applying scoreboard updates
 * through the wrong path.
 */
@Configuration
public class ContestScoreboardStoreConfig {

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
    InMemoryContestScoreboard inMemoryContestScoreboard() {
        return new InMemoryContestScoreboard();
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
    ContestScoreboardApplier inMemoryContestScoreboardApplier(InMemoryContestScoreboard scoreboard) {
        return new InMemoryContestScoreboardApplier(scoreboard);
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
    RedisContestScoreboardApplyMetrics redisContestScoreboardApplyMetrics(MeterRegistry meterRegistry) {
        return new RedisContestScoreboardApplyMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
    ContestScoreboardApplier redisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                                           ContestRedisKeyValueClient redisClient,
                                                           RedisContestScoreboardApplyMetrics metrics) {
        return new RedisContestScoreboardApplier(redisTemplate, redisClient, metrics);
    }
}
