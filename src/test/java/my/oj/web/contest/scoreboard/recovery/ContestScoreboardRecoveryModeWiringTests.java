package my.oj.web.contest.scoreboard.recovery;

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
 */
class ContestScoreboardRecoveryModeWiringTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, ContestScoreboardFullReplayStartupRunner.class);

    @Test
    void theReplayServiceIsAvailableInEveryMode() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ContestScoreboardFullReplayService.class);
            assertThat(context).doesNotHaveBean(ContestScoreboardFullReplayStartupRunner.class);
        });
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

    @Configuration
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    static class Dependencies {

        @Bean
        ContestScoreboardFullReplayService fullReplayService() {
            return mock(ContestScoreboardFullReplayService.class);
        }
    }
}
