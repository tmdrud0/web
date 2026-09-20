package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.stream.ContestScoreboardStreamRecoveryService;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which recovery beans a mode brings up.
 *
 * <p>The gate is an explicit {@code havingValue} with no {@code matchIfMissing}, like
 * {@code contest.scoreboard.store}: dropping a branch has to leave the context without the bean and
 * fail loudly rather than quietly run a weaker recovery than the operator asked for.</p>
 *
 * <p>The replay service itself is deliberately not conditional. Only what <em>triggers</em> a replay
 * depends on the mode, because the retention-gap fallback also replays through this service.</p>
 *
 * <p>Unlike the replay service, the sequence recovery is conditional on the mode. Nothing else runs
 * a sequence check, and the sequence state it reads lives only beside a Redis scoreboard, so an
 * unconditional service would leave the default {@code store=memory} configuration unable to start.</p>
 *
 * <p>The strategy is the mode made into an object, and there is exactly one of it. Its bean is
 * selected by an exhaustive switch over the bound mode rather than by {@code @ConditionalOnProperty}
 * on the raw string, so a mode added to the enum without a branch fails the build instead of quietly
 * selecting no strategy - which is what the relaxed-spelling test below is about, seen from the other
 * side.</p>
 */
class ContestScoreboardRecoveryModeWiringTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // The strategy is the live path's decision-maker as well as the supervisor's, so it exists
            // where the consumer does - and only there. Both are gated on this one property.
            .withPropertyValues("contest.scoreboard.stream.consumer.enabled=true")
            .withUserConfiguration(
                    Dependencies.class,
                    // The production classes, not mocks: this runner's job is to decide whether a bean
                    // comes up in every mode, and a bean the test itself registers would answer that
                    // whichever way the service were annotated.
                    ContestScoreboardFullReplayService.class,
                    ContestScoreboardStreamRecoveryService.class,
                    ContestScoreboardRecoveryStrategyConfig.class,
                    ContestScoreboardFullReplayStartupRunner.class,
                    ContestScoreboardRedisSequenceConfig.class,
                    ContestScoreboardRedisSequenceRecoveryService.class,
                    ContestScoreboardRedisSequenceScheduler.class,
                    ContestScoreboardRedisSequenceStartupCheck.class
            );

    /**
     * Every mode gets a strategy, each mode gets its own, and the one thing the supervisor reads from
     * it - whether that mode repairs a rollback by re-reading the stream - is what the mode chose.
     * A single strategy shared by all three, or a mode falling through to another's, would make the
     * modes indistinguishable at the one place they are supposed to differ.
     */
    @Test
    void eachModeBringsUpItsOwnRecoveryStrategy() {
        assertStrategy("stream-offset", ContestScoreboardRecoveryMode.STREAM_OFFSET, true);
        assertStrategy("full-replay", ContestScoreboardRecoveryMode.FULL_REPLAY, false);
        assertStrategy("redis-seq", ContestScoreboardRecoveryMode.REDIS_SEQ, false);
    }

    /**
     * The strategy is not created where nothing could use it. A mode with no consumer has no live
     * path and no supervisor, so a strategy there would be a bean that decides nothing - and the
     * point of gating it is that the same {@code enabled} property governs both.
     */
    @Test
    void noStrategyComesUpWithoutTheConsumer() {
        new ApplicationContextRunner()
                .withPropertyValues("contest.scoreboard.stream.consumer.enabled=false")
                .withUserConfiguration(
                        Dependencies.class,
                        ContestScoreboardFullReplayService.class,
                        ContestScoreboardRecoveryStrategyConfig.class
                )
                .run(context -> assertThat(context).doesNotHaveBean(ContestScoreboardRecoveryStrategy.class));
    }

    /**
     * The service is unconditional - the retention-gap fallback replays through it in every mode, so
     * a mode without it could not bridge a gap. The modes other than its own are checked as well as
     * the default, because "available in every mode" is the claim.
     */
    @Test
    void theReplayServiceIsAvailableInEveryMode() {
        for (String mode : new String[]{"stream-offset", "full-replay", "redis-seq"}) {
            contextRunner
                    .withPropertyValues("contest.scoreboard.recovery.mode=" + mode)
                    .run(context -> assertThat(context)
                            .as("mode=%s", mode)
                            .hasSingleBean(ContestScoreboardFullReplayService.class));
        }
    }

    @Test
    void fullReplayModeIsTheOnlyOneThatReplaysAtStartup() {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=full-replay")
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestScoreboardFullReplayStartupRunner.class);
                    assertThat(context).hasSingleBean(ContestScoreboardFullReplayService.class);
                });
    }

    @Test
    void theOtherModesDoNotReplayAtStartup() {
        for (String mode : new String[]{"stream-offset", "redis-seq"}) {
            contextRunner
                    .withPropertyValues("contest.scoreboard.recovery.mode=" + mode)
                    .run(context -> assertThat(context)
                            .as("mode=%s", mode)
                            .doesNotHaveBean(ContestScoreboardFullReplayStartupRunner.class));
        }
    }

    /**
     * The sequence surface exists in its own mode and nowhere else - the meters included, because a
     * duplicate counter that is registered by a mode which never checks for duplicates reads as a
     * healthy zero.
     */
    @Test
    void onlyRedisSeqModeBringsUpTheSequenceCheck() {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=redis-seq")
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestScoreboardRedisSequenceRecoveryService.class);
                    assertThat(context).hasSingleBean(ContestScoreboardRedisSequenceScheduler.class);
                    assertThat(context).hasSingleBean(ContestScoreboardRedisSequenceStartupCheck.class);
                    assertThat(context).hasSingleBean(ContestScoreboardRedisSequenceMetrics.class);
                });

        for (String mode : new String[]{"stream-offset", "full-replay"}) {
            contextRunner
                    .withPropertyValues("contest.scoreboard.recovery.mode=" + mode)
                    .run(context -> {
                        assertThat(context)
                                .as("mode=%s", mode)
                                .doesNotHaveBean(ContestScoreboardRedisSequenceStartupCheck.class);
                        assertThat(context)
                                .as("mode=%s", mode)
                                .doesNotHaveBean(ContestScoreboardRedisSequenceMetrics.class);
                    });
        }
    }

    /**
     * A value that names no mode must not silently fall back to one the operator did not choose.
     *
     * <p>The failure is the assertion: a context that did not start cannot have brought up the
     * mode's beans.</p>
     */
    @Test
    void aValueThatNamesNoModeStopsTheApplication() {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=rebuild-everything")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * The reason {@link ContestScoreboardRecoveryValidator} refuses a non-canonical spelling: enum
     * binding is lenient about case and separators, so {@code FULL_REPLAY} binds to
     * {@link ContestScoreboardRecoveryMode#FULL_REPLAY} while this gate - which compares the string
     * as written - selects nothing. The application would report mode=full-replay and run no mode at
     * all, so the validator stops it before it gets that far.
     */
    @Test
    void aRelaxedSpellingSelectsNoModeBeansOnItsOwn() {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=FULL_REPLAY")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ContestScoreboardFullReplayStartupRunner.class);
                });
    }

    private void assertStrategy(String mode,
                                ContestScoreboardRecoveryMode expected,
                                boolean rewindsOnCheckpointRegression) {
        contextRunner
                .withPropertyValues("contest.scoreboard.recovery.mode=" + mode)
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestScoreboardRecoveryStrategy.class);
                    ContestScoreboardRecoveryStrategy strategy =
                            context.getBean(ContestScoreboardRecoveryStrategy.class);
                    assertThat(strategy.mode()).as("mode=%s", mode).isEqualTo(expected);
                    assertThat(strategy.rewindsOnCheckpointRegression())
                            .as("mode=%s", mode)
                            .isEqualTo(rewindsOnCheckpointRegression);
                });
    }

    @Configuration
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    static class Dependencies {

        @Bean
        ContestScoreboardApplier scoreboardApplier() {
            return mock(ContestScoreboardApplier.class);
        }

        @Bean
        ContestScoreboardSequenceSource sequenceSource() {
            return mock(ContestScoreboardSequenceSource.class);
        }

        @Bean
        ContestScoreboardApplyLock applyLock() {
            return mock(ContestScoreboardApplyLock.class);
        }

        @Bean
        ContestScoreboardAppliedMarker appliedMarker() {
            return mock(ContestScoreboardAppliedMarker.class);
        }

        @Bean
        ContestSubmissionResultRepository resultRepository() {
            return mock(ContestSubmissionResultRepository.class);
        }

        @Bean
        ContestSubmissionBatchExecutor batchExecutor() {
            return mock(ContestSubmissionBatchExecutor.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ContestScoreboardRecoveryPassGate recoveryPassGate(MeterRegistry meterRegistry) {
            return new ContestScoreboardRecoveryPassGate(meterRegistry);
        }
    }
}
