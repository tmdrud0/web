package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.delivery.ContestScoreboardDelivery;
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

    /**
     * redis-seq reports the delivery and the mysql-poll settings it runs with, and nothing of the removed
     * Stream-driven checks - a report naming a duplicate-check interval would describe a check that no
     * longer exists.
     */
    @Test
    void reportsTheSettingsTheSelectedModeActuallyReads() {
        contextRunner
                .withPropertyValues(
                        "contest.scoreboard.recovery.mode=redis-seq",
                        "contest.scoreboard.delivery=mysql-poll",
                        "contest.scoreboard.mysql-poll.batch-size=250",
                        "contest.scoreboard.mysql-poll.poll-interval=175ms",
                        "contest.scoreboard.mysql-poll.recovery-max-iterations=7",
                        "contest.scoreboard.recovery.redis-seq.check-window-size=250"
                )
                .run(context -> {
                    ContestScoreboardRecoveryProperties properties =
                            context.getBean(ContestScoreboardRecoveryProperties.class);
                    String summary = ContestScoreboardRecoverySummary.describe(
                            properties.mode(), "redis", ContestScoreboardDelivery.of(context.getEnvironment()),
                            properties, ContestScoreboardRecoveryReporter.pollProperties(context.getEnvironment()));

                    assertThat(summary)
                            .contains("mode=redis-seq")
                            .contains("store=redis")
                            .contains("recovery-owner=true")
                            .contains("delivery=mysql-poll")
                            .contains("batch-size=250")
                            .contains("poll-interval=175ms")
                            .contains("rollback-check-interval=5s")
                            .contains("recovery-interval=1s")
                            .contains("recovery-chunk-size=500")
                            .contains("recovery-max-iterations=7")
                            .doesNotContain("duplicate-check-interval")
                            .doesNotContain("check-window-size");
                });
    }

    @Test
    void reportsTheDefaultStoreWhenNothingSelectsOne() {
        contextRunner.run(context -> {
            ContestScoreboardRecoveryProperties properties =
                    context.getBean(ContestScoreboardRecoveryProperties.class);

            assertThat(ContestScoreboardStoreProperty.value(new MockEnvironment())).isEqualTo("memory");
            assertThat(ContestScoreboardRecoverySummary.describe(properties.mode(), "memory",
                    ContestScoreboardDelivery.of(context.getEnvironment()), properties,
                    ContestScoreboardRecoveryReporter.pollProperties(context.getEnvironment())))
                    .contains("store=memory")
                    .contains("delivery=rabbit-stream");
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

    /**
     * The owner declaration is checked because everything that enforces "one recovery pass" is
     * JVM-local, so a role that contradicts what it runs is a silent second owner. A consumer runs the
     * supervisor pass whether or not it says so, and the declaration must not be able to deny it.
     */
    @Test
    void refusesAConsumerThatDeclaresItselfNoOwner() {
        MockEnvironment environment = environment("stream-offset", "redis");
        environment.setProperty(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY, "true");

        assertThatThrownBy(() -> new ContestScoreboardRecoveryValidator(
                properties("stream-offset", false), environment).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContestScoreboardRecoveryValidator.OWNER_PROPERTY)
                .hasMessageContaining("recovery owner whether it declares itself one or not");
    }

    /**
     * The other direction: {@code stream-offset} has no trigger but the consumer's supervisor pass, so
     * an owner with the consumer off owns nothing while reporting that it recovers.
     */
    @Test
    void refusesAnOwnerWithNoTriggerInTheModeThatNeedsTheConsumer() {
        assertThatThrownBy(() -> validator("stream-offset", "stream-offset", "memory", true, false)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContestScoreboardRecoveryValidator.OWNER_PROPERTY)
                .hasMessageContaining("would own nothing");
    }

    /**
     * The other two modes trigger without the consumer, so an owner with the consumer off is a real
     * configuration there rather than an empty declaration.
     */
    @Test
    void allowsAnOwnerWithNoConsumerInTheModesThatDoNotNeedOne() {
        assertThatCode(() -> validator("full-replay", "full-replay", "memory", true, false)
                .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("redis-seq", "redis-seq", "redis", true, false)
                .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
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

    /**
     * A consumer whose mode's only release of the history-recovery hold is off is refused, because the
     * hold is what keeps the mode's own basis as the thing that rebuilds the history a restored Redis is
     * missing. {@code full-replay} is that mode: its startup runner is the one thing that reports the
     * boundary, and the retention-gap fallback - the only other caller of the replay - is reached through
     * the consumer that is waiting. Nothing would report it, so the instance would consume nothing.
     */
    @Test
    void refusesAConsumerWhoseModesOnlyReleaseIsOff() {
        assertThatThrownBy(() -> consumerWith("full-replay", false, true).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContestScoreboardRecoveryValidator.STARTUP_REPLAY_PROPERTY)
                .hasMessageContaining("the consumer is held until that replay has run")
                .hasMessageContaining("mode=full-replay");
    }

    /**
     * redis-seq is delivered by the MySQL poller, so a role that would also consume the scoreboard Stream
     * is refused: the same judged result could reach the scoreboard through both.
     */
    @Test
    void refusesAStreamConsumerUnderTheMySqlPoller() {
        assertThatThrownBy(() -> consumerWith("redis-seq", true, true).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY)
                .hasMessageContaining("mysql-poll");
    }

    @Test
    void refusesAJudgeResultStreamPublisherUnderTheMySqlPoller() {
        MockEnvironment environment = environment("redis-seq", "redis");
        environment.setProperty(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY, "false");
        environment.setProperty(ContestScoreboardRecoveryValidator.RESULT_STREAM_PUBLISHER_PROPERTY, "true");
        ContestScoreboardRecoveryValidator validator = new ContestScoreboardRecoveryValidator(
                properties("redis-seq", true, true, true), environment);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ContestScoreboardRecoveryValidator.RESULT_STREAM_PUBLISHER_PROPERTY);
    }

    /** The pairs that are not supported are refused by name, in both directions. */
    @Test
    void refusesADeliveryTheModeCannotUse() {
        assertThatThrownBy(() -> deliveryValidator("redis-seq", "rabbit-stream", "redis").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mode=redis-seq")
                .hasMessageContaining("delivery=rabbit-stream");
        assertThatThrownBy(() -> deliveryValidator("redis-seq", null, "redis").afterSingletonsInstantiated())
                .as("an unset delivery is rabbit-stream, which redis-seq cannot use")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deliveryValidator("stream-offset", "mysql-poll", "redis").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mode=stream-offset")
                .hasMessageContaining("delivery=mysql-poll");
        assertThatThrownBy(() -> deliveryValidator("full-replay", "mysql-poll", "redis").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mode=full-replay");
    }

    @Test
    void refusesADeliverySpellingOtherThanTheCanonicalOne() {
        assertThatThrownBy(() -> deliveryValidator("redis-seq", "MYSQL_POLL", "redis").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be written as mysql-poll");
        assertThatThrownBy(() -> deliveryValidator("redis-seq", "kafka", "redis").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsTheSupportedDeliveryPairs() {
        assertThatCode(() -> deliveryValidator("redis-seq", "mysql-poll", "redis").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> deliveryValidator("stream-offset", "rabbit-stream", "redis").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> deliveryValidator("full-replay", "rabbit-stream", "memory").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    /**
     * The consumer's flag is read as {@code @ConditionalOnProperty} reads it, not through a lenient
     * {@code Boolean.class} binding. A spelling the condition turns down registers no consumer at all, so
     * refusing it would be a false refusal caused only by the spelling - here on a role that declares
     * itself no recovery owner, which is exactly the shape a web or judge role has.
     */
    @Test
    void doesNotRefuseASpellingTheConsumerConditionItselfTurnsDown() {
        MockEnvironment environment = environment("full-replay", "memory");
        environment.setProperty(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY, "yes");
        ContestScoreboardRecoveryValidator validator = new ContestScoreboardRecoveryValidator(
                properties("full-replay", false, false, true), environment);

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    /**
     * The other half of that refusal: turning the startup pass off is an ordinary setting on a role that
     * does not consume the stream, because there the pass is not what anything is waiting behind.
     * {@code stream-offset} is unaffected either way - its history recovery is the consumer's own
     * re-read, so it has no startup pass to turn off and nothing to hold it.
     */
    @Test
    void allowsAStartupPassTurnedOffOnARoleThatDoesNotConsume() {
        assertThatCode(() -> validator("full-replay", "full-replay", "memory", true, false, false, true)
                .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("redis-seq", "redis-seq", "redis", true, false, true, false)
                .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> validator("stream-offset", "stream-offset", "memory", true, true, false, false)
                .afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    /** Both Stream modes may consume the stream with their own startup pass on, which is the shipped default. */
    @Test
    void allowsEveryModeToConsumeBehindItsOwnStartupPass() {
        assertThatCode(() -> consumerWith("stream-offset", true, true).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
        assertThatCode(() -> consumerWith("full-replay", true, true).afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    /** A role that consumes nothing and publishes nothing, with the delivery written as given. */
    private static ContestScoreboardRecoveryValidator deliveryValidator(String mode, String delivery, String store) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(ContestScoreboardRecoveryValidator.MODE_PROPERTY, mode);
        environment.setProperty(ContestScoreboardStoreProperty.NAME, store);
        environment.setProperty(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY, "false");
        if (delivery != null) {
            environment.setProperty(ContestScoreboardRecoveryValidator.DELIVERY_PROPERTY, delivery);
        }
        // Not the owner: stream-offset's "owner without a consumer" refusal is a different rule.
        return new ContestScoreboardRecoveryValidator(properties(mode, false, true, true), environment);
    }

    private void assertSummary(String mode, String store) {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=" + mode,
                        "contest.scoreboard.delivery=" + ("redis-seq".equals(mode) ? "mysql-poll" : "rabbit-stream"))
                .run(context -> {
                    ContestScoreboardRecoveryProperties properties =
                            context.getBean(ContestScoreboardRecoveryProperties.class);
                    String summary = ContestScoreboardRecoverySummary.describe(properties.mode(), store,
                            ContestScoreboardDelivery.of(context.getEnvironment()), properties,
                            ContestScoreboardRecoveryReporter.pollProperties(context.getEnvironment()));

                    assertThat(summary)
                            .startsWith("mode=" + mode + " store=" + store + " recovery-owner=true delivery=")
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
        // redis-seq never consumes the Stream; its delivery is the MySQL poller.
        return validator(configuredMode, mode, store, true, !"redis-seq".equals(mode));
    }

    private static ContestScoreboardRecoveryValidator validator(String configuredMode,
                                                                String mode,
                                                                String store,
                                                                boolean ownerEnabled,
                                                                boolean consumerEnabled) {
        return validator(configuredMode, mode, store, ownerEnabled, consumerEnabled, true, true);
    }

    /** A context that consumes the stream in the named mode, with the startup pass on unless said not to. */
    private static ContestScoreboardRecoveryValidator consumerWith(String mode,
                                                                  boolean startupReplayEnabled,
                                                                  boolean startupCheckEnabled) {
        String store = "redis-seq".equals(mode) ? "redis" : "memory";
        return validator(mode, mode, store, true, true, startupReplayEnabled, startupCheckEnabled);
    }

    private static ContestScoreboardRecoveryValidator validator(String configuredMode,
                                                                String mode,
                                                                String store,
                                                                boolean ownerEnabled,
                                                                boolean consumerEnabled,
                                                                boolean startupReplayEnabled,
                                                                boolean startupCheckEnabled) {
        MockEnvironment environment = environment(configuredMode, store);
        environment.setProperty(ContestScoreboardRecoveryValidator.STREAM_CONSUMER_PROPERTY,
                Boolean.toString(consumerEnabled));
        return new ContestScoreboardRecoveryValidator(
                properties(mode, ownerEnabled, startupReplayEnabled, startupCheckEnabled), environment);
    }

    private static MockEnvironment environment(String configuredMode, String store) {
        MockEnvironment environment = new MockEnvironment();
        if (configuredMode != null) {
            environment.setProperty(ContestScoreboardRecoveryValidator.MODE_PROPERTY, configuredMode);
        }
        environment.setProperty(ContestScoreboardStoreProperty.NAME, store);
        if ("redis-seq".equals(configuredMode)) {
            environment.setProperty(ContestScoreboardRecoveryValidator.DELIVERY_PROPERTY, "mysql-poll");
        }
        return environment;
    }

    private static ContestScoreboardRecoveryProperties properties(String mode) {
        return properties(mode, true);
    }

    private static ContestScoreboardRecoveryProperties properties(String mode, boolean ownerEnabled) {
        return properties(mode, ownerEnabled, true, true);
    }

    private static ContestScoreboardRecoveryProperties properties(String mode,
                                                                 boolean ownerEnabled,
                                                                 boolean startupReplayEnabled,
                                                                 boolean startupCheckEnabled) {
        return new ContestScoreboardRecoveryProperties(
                Arrays.stream(ContestScoreboardRecoveryMode.values())
                        .filter(candidate -> candidate.propertyValue().equals(mode))
                        .findFirst()
                        .orElseThrow(),
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, startupReplayEnabled),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        1000,
                        10,
                        5,
                        500,
                        3,
                        Duration.ofMillis(50),
                        startupCheckEnabled
                ),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED
                ),
                new ContestScoreboardRecoveryProperties.RecoveryOwner(ownerEnabled)
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    static class PropertiesConfiguration {
    }
}
