package my.oj.web.contest.scoreboard.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recovery mode and its settings are the switch this whole change is selected by, so an
 * unusable value has to stop the JVM rather than fall back to something weaker.
 */
class ContestScoreboardRecoveryPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsTheDefaultModeAndEveryRecoveryDefault() {
        contextRunner.run(context -> {
            ContestScoreboardRecoveryProperties properties =
                    context.getBean(ContestScoreboardRecoveryProperties.class);

            assertThat(properties.mode()).isEqualTo(ContestScoreboardRecoveryMode.STREAM_OFFSET);
            assertThat(properties.fullReplay().dbBatchSize()).isEqualTo(1000);
            assertThat(properties.fullReplay().replayBatchSize()).isEqualTo(500);
            assertThat(properties.redisSeq().duplicateCheckInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.redisSeq().lostTailCheckInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.redisSeq().checkWindowSize()).isEqualTo(1000);
            assertThat(properties.redisSeq().maxWindowsPerPass()).isEqualTo(10);
            assertThat(properties.redisSeq().maxIterations()).isEqualTo(5);
            assertThat(properties.redisSeq().replayBatchSize()).isEqualTo(500);
            assertThat(properties.redisSeq().retryMaxAttempts()).isEqualTo(3);
            assertThat(properties.redisSeq().retryBackoff()).isEqualTo(Duration.ofMillis(50));
            assertThat(properties.redisSeq().startupCheckEnabled()).isTrue();
            assertThat(properties.streamOffset().retentionGapFallback())
                    .isEqualTo(ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY);
            assertThat(properties.streamOffset().startupOffset())
                    .isEqualTo(ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED);
        });
    }

    @Test
    void bindsEachModeFromItsPropertyValue() {
        for (ContestScoreboardRecoveryMode mode : ContestScoreboardRecoveryMode.values()) {
            contextRunner
                    .withPropertyValues("contest.scoreboard.recovery.mode=" + mode.propertyValue())
                    .run(context -> assertThat(context.getBean(ContestScoreboardRecoveryProperties.class).mode())
                            .isEqualTo(mode));
        }
    }

    @Test
    void rejectsAnUnknownModeValue() {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=rebuild-everything")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("rebuild-everything");
                });
    }

    @Test
    void rejectsAnUnknownFallbackOrStartupOffsetValue() {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.stream-offset.retention-gap-fallback=maybe")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.stream-offset.startup-offset=latest")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRecoverySettingsThatWouldSilentlyWeakenTheChecks() {
        assertRejected("contest.scoreboard.recovery.full-replay.db-batch-size=0");
        assertRejected("contest.scoreboard.recovery.full-replay.replay-batch-size=0");
        assertRejected("contest.scoreboard.recovery.redis-seq.check-window-size=0");
        assertRejected("contest.scoreboard.recovery.redis-seq.max-windows-per-pass=0");
        assertRejected("contest.scoreboard.recovery.redis-seq.max-iterations=0");
        assertRejected("contest.scoreboard.recovery.redis-seq.replay-batch-size=0");
        assertRejected("contest.scoreboard.recovery.redis-seq.retry-max-attempts=0");
    }

    private void assertRejected(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isNotNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    static class PropertiesConfiguration {
    }
}
