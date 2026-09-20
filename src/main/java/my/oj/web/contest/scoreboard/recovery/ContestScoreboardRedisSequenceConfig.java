package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code redis-seq} mode's own meters.
 *
 * <p>Registered only in that mode, so a deployment running one of the other two does not publish a
 * family of counters that will never move - an always-zero duplicate count would be
 * indistinguishable from a check that is not running at all.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "redis-seq"
)
public class ContestScoreboardRedisSequenceConfig {

    @Bean
    ContestScoreboardRedisSequenceMetrics contestScoreboardRedisSequenceMetrics(MeterRegistry meterRegistry) {
        return new ContestScoreboardRedisSequenceMetrics(meterRegistry);
    }
}
