package my.oj.web.contest.scoreboard.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The requirement is that the selected mode and the values it runs with are observable at runtime,
 * so both the report line and the store guard are asserted here instead of being eyeballed in a log.
 */
class ContestScoreboardRecoverySummaryTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void reportsTheModeThatEachConfigurationSelects() {
        assertSummary("stream-offset", "redis");
        assertSummary("full-replay", "redis");
        assertSummary("redis-seq", "redis");
    }

    @Test
    void reportsTheSettingsTheSelectedModeActuallyReads() {
        contextRunner
                .withPropertyValues(
                        "contest.scoreboard.recovery.mode=redis-seq",
                        "contest.scoreboard.recovery.redis-seq.check-window-size=250",
                        "contest.scoreboard.recovery.redis-seq.max-iterations=7",
                        "contest.scoreboard.recovery.redis-seq.retry-backoff=175ms",
                        "contest.scoreboard.recovery.redis-seq.startup-check-enabled=false"
                )
                .run(context -> {
                    ContestScoreboardRecoveryProperties properties =
                            context.getBean(ContestScoreboardRecoveryProperties.class);
                    String summary = ContestScoreboardRecoverySummary.describe(
                            properties.mode(), "redis", properties);

                    assertThat(summary)
                            .contains("mode=redis-seq")
                            .contains("store=redis")
                            .contains("duplicate-check-interval=30s")
                            .contains("lost-tail-check-interval=30s")
                            .contains("check-window-size=250")
                            .contains("max-windows-per-pass=10")
                            .contains("max-iterations=7")
                            .contains("retry-backoff=175ms")
                            .contains("startup-check-enabled=false");
                });
    }

    @Test
    void reportsTheDefaultStoreWhenNothingSelectsOne() {
        contextRunner.run(context -> {
            ContestScoreboardRecoveryProperties properties =
                    context.getBean(ContestScoreboardRecoveryProperties.class);

            assertThat(ContestScoreboardStoreProperty.value(new MockEnvironment())).isEqualTo("memory");
            assertThat(ContestScoreboardRecoverySummary.describe(properties.mode(), "memory", properties))
                    .contains("store=memory");
        });
    }

    @Test
    void refusesSequenceRecoveryWithoutARedisStore() {
        assertThatThrownBy(() -> validator("redis-seq", "memory").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis-seq")
                .hasMessageContaining("memory");
    }

    @Test
    void allowsEveryOtherModeAndStoreCombination() {
        assertThatCode(() -> validator("redis-seq", "redis").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("stream-offset", "memory").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("stream-offset", "redis").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("full-replay", "memory").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    /**
     * Enum binding is lenient, but the gate that selects a mode's beans compares the property string
     * as written. A spelling the two disagree on would report one mode and run none, so it is
     * rejected instead.
     */
    @Test
    void refusesAModeSpellingThatWouldSelectNoModeBeans() {
        assertThatThrownBy(() -> validator("FULL_REPLAY", "full-replay", "memory").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FULL_REPLAY")
                .hasMessageContaining("must be written as full-replay");
    }

    @Test
    void allowsTheCanonicalModeSpellingAndAnAbsentMode() {
        assertThatCode(() -> validator("full-replay", "full-replay", "memory").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        // Nothing configured means the record's own default applies, which is canonical by
        // construction and therefore needs no spelling to check.
        assertThatCode(() -> validator(null, "full-replay", "memory").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    private void assertSummary(String mode, String store) {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=" + mode)
                .run(context -> {
                    ContestScoreboardRecoveryProperties properties =
                            context.getBean(ContestScoreboardRecoveryProperties.class);
                    String summary = ContestScoreboardRecoverySummary.describe(properties.mode(), store, properties);

                    assertThat(summary)
                            .startsWith("mode=" + mode + " store=" + store + " ")
                            .doesNotContain("null");
                });
    }

    private static ContestScoreboardRecoveryValidator validator(String mode, String store) {
        return validator(mode, mode, store);
    }

    /**
     * @param configuredMode what the environment holds, or null for "not configured"
     * @param mode           the mode that value binds to
     */
    private static ContestScoreboardRecoveryValidator validator(String configuredMode,
                                                                String mode,
                                                                String store) {
        MockEnvironment environment = new MockEnvironment();
        if (configuredMode != null) {
            environment.setProperty(ContestScoreboardRecoveryValidator.MODE_PROPERTY, configuredMode);
        }
        environment.setProperty(ContestScoreboardStoreProperty.NAME, store);
        return new ContestScoreboardRecoveryValidator(properties(mode), environment);
    }

    private static ContestScoreboardRecoveryProperties properties(String mode) {
        return new ContestScoreboardRecoveryProperties(
                Arrays.stream(ContestScoreboardRecoveryMode.values())
                        .filter(candidate -> candidate.propertyValue().equals(mode))
                        .findFirst()
                        .orElseThrow(),
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        1000,
                        10,
                        5,
                        1000,
                        500,
                        3,
                        Duration.ofMillis(50),
                        true
                ),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED
                )
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    static class PropertiesConfiguration {
    }
}
