package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardOutboxApplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Selects the outbox applier from {@code contest.scoreboard.store}, tracking whichever
 * scoreboard store that property selected.
 *
 * <p>Both branches state their value explicitly, so an unrecognised setting leaves no bean
 * and the application fails to start rather than silently applying scoreboard updates
 * through the wrong path.
 */
@Configuration
public class ContestScoreboardOutboxApplierConfig {

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
    ContestScoreboardOutboxApplier redisContestScoreboardOutboxApplier(StringRedisTemplate redisTemplate) {
        return new RedisContestScoreboardOutboxApplier(redisTemplate);
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
    ContestScoreboardOutboxApplier directContestScoreboardOutboxApplier(
            ContestScoreboardService scoreboardService
    ) {
        return new DirectContestScoreboardOutboxApplier(scoreboardService);
    }
}
