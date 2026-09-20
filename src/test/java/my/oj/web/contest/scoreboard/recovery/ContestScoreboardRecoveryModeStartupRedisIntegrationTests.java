package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the {@code redis-seq} mode starts a real application, not just a sliced context.
 *
 * <p>{@link ContestScoreboardRecoveryModeWiringTests} decides bean selection from an
 * {@code ApplicationContextRunner} with the recovery beans and their dependencies mocked. That
 * answers which beans a mode brings up, and cannot answer the other half: whether the mode's beans
 * coexist with the rest of the application - the validator, the bound properties, the Redis-backed
 * sequence source - when the JVM actually boots. Those are the failure modes that matter, because
 * they are the ones an operator meets.</p>
 *
 * <p>{@code stream-offset} and {@code full-replay} boot for real elsewhere in the suite
 * ({@code ContestPipelineWiringTests} runs the default properties, and
 * {@code ContestScoreboardFullReplayRedisIntegrationTests} pins {@code mode=full-replay}). The
 * sequence mode is the one nothing else starts, so it is the one started here.</p>
 *
 * <p>Both periodic checks are pushed an hour out and the startup check is turned off: this asserts
 * what the mode brings up, and a pass that ran here would be reading and replaying against whatever
 * the shared Redis happens to hold.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.scoreboard.recovery.mode=redis-seq",
        "contest.scoreboard.recovery.redis-seq.startup-check-enabled=false",
        "contest.scoreboard.recovery.redis-seq.duplicate-check-interval=1h",
        "contest.scoreboard.recovery.redis-seq.lost-tail-check-interval=1h",
        "contest.scoreboard.stream.consumer.enabled=false",
        "rank.streak.batch.enabled=false"
})
@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class ContestScoreboardRecoveryModeStartupRedisIntegrationTests {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.getInteger("redisPort", 16379));
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ContestScoreboardRecoveryProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void theSequenceModeBootsWithItsOwnBeansAndReportsItself() {
        assertThat(properties.mode()).isEqualTo(ContestScoreboardRecoveryMode.REDIS_SEQ);
        assertThat(context.getBeanNamesForType(ContestScoreboardRecoveryReporter.class))
                .as("the startup report the operator reads the selected mode from")
                .hasSize(1);
        assertThat(context.getBeanNamesForType(ContestScoreboardRedisSequenceScheduler.class))
                .as("the periodic trigger this mode runs on")
                .hasSize(1);
        assertThat(context.getBeanNamesForType(ContestScoreboardRedisSequenceStartupCheck.class))
                .as("the mode-conditional startup check, present even with the check itself disabled")
                .hasSize(1);
        // The meters come from a mode-conditional configuration class rather than from the service,
        // so this is also the assertion that the configuration class was selected.
        assertThat(context.getBeanNamesForType(ContestScoreboardRedisSequenceMetrics.class)).hasSize(1);
        assertThat(meterRegistry.find("contest.scoreboard.redis.sequence.duplicates").counter())
                .as("the duplicate counter is registered by this mode alone")
                .isNotNull();
        // The startup report is a pure function of exactly these inputs, which is what it prints.
        assertThat(ContestScoreboardRecoverySummary.describe(properties.mode(), "redis", properties))
                .startsWith("mode=redis-seq store=redis");
    }
}
