package my.oj.web.contest.scoreboard;

import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboard;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardApplier;
import my.oj.web.contest.scoreboard.redis.ContestRedisKeyValueClient;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplier;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplyMetrics;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * Whether the scoreboard issues a recovery sequence, read from the mode as bound rather than as
     * written. One unconditional bean, so a context that never loads the recovery properties - a
     * slice test, or a profile that does not use the feature - gets the documented default of no
     * sequence rather than failing to start.
     */
    @Bean
    ContestScoreboardSequenceTracking contestScoreboardSequenceTracking(
            ObjectProvider<ContestScoreboardRecoveryProperties> recoveryProperties) {
        ContestScoreboardRecoveryProperties properties = recoveryProperties.getIfAvailable();
        return () -> properties != null && properties.mode() == ContestScoreboardRecoveryMode.REDIS_SEQ;
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
    InMemoryContestScoreboard inMemoryContestScoreboard() {
        return new InMemoryContestScoreboard();
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
    InMemoryContestScoreboardApplier inMemoryContestScoreboardApplier(
            InMemoryContestScoreboard scoreboard,
            ContestScoreboardSequenceTracking sequenceTracking) {
        return new InMemoryContestScoreboardApplier(scoreboard, sequenceTracking);
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
                                                           RedisContestScoreboardApplyMetrics metrics,
                                                           ContestScoreboardSequenceTracking sequenceTracking) {
        return new RedisContestScoreboardApplier(redisTemplate, redisClient, metrics, sequenceTracking);
    }

    @Bean
    @ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
    ContestScoreboardSequenceSource redisContestScoreboardSequenceSource(StringRedisTemplate redisTemplate) {
        return new RedisContestScoreboardSequenceSource(redisTemplate);
    }
}
