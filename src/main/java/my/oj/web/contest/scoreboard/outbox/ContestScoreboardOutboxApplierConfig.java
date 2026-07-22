package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ContestScoreboardOutboxApplierConfig {

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
    ContestScoreboardOutboxApplier redisContestScoreboardOutboxApplier(StringRedisTemplate redisTemplate) {
        return new RedisContestScoreboardOutboxApplier(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(ContestScoreboardOutboxApplier.class)
    ContestScoreboardOutboxApplier directContestScoreboardOutboxApplier(
            ContestScoreboardService scoreboardService
    ) {
        return new DirectContestScoreboardOutboxApplier(scoreboardService);
    }
}
