package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.poll.ContestScoreboardMySqlPollLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the {@code full-replay} mode starts a real application.
 *
 * <p>Nothing else does. {@link ContestScoreboardRecoveryModeWiringTests} decides bean selection from
 * an {@code ApplicationContextRunner}, and the real-context test beside the full replay service
 * deliberately leaves the mode at its default and calls the service directly - the service exists in
 * every mode, so only the runner and the startup wiring are mode-conditional, and it is those that
 * go unstarted. A mode whose beans exist but which no JVM has ever booted is not a mode an operator
 * can select.</p>
 *
 * <p>{@code startup-replay-enabled=false} keeps this from replaying every contest in the shared test
 * schema. What is asserted is that the mode boots and reports itself, not what the replay does - the
 * replay's own tests seed and assert a single contest.</p>
 *
 * <p>{@code owner.enabled=true} is declared because the test profile declares the opposite -
 * {@code application-test.properties} sets no context as the owner, so that a test which starts a
 * recovery pass has to say so. This one does: the whole assertion is that the mode's startup runner
 * comes up in a real boot, and the runner is absent from a context that is not the owner.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.recovery.mode=full-replay",
        "contest.scoreboard.recovery.full-replay.startup-replay-enabled=false",
        "contest.scoreboard.recovery.owner.enabled=true",
        "rank.streak.batch.enabled=false"
})
class ContestScoreboardRecoveryModeStartupTests {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ContestScoreboardRecoveryProperties properties;

    @Test
    void theReplayModeBootsWithItsStartupRunnerAndReportsItself() {
        assertThat(properties.mode()).isEqualTo(ContestScoreboardRecoveryMode.FULL_REPLAY);
        assertThat(properties.fullReplay().startupReplayEnabled()).isFalse();
        assertThat(context.getBeanNamesForType(ContestScoreboardRecoveryReporter.class))
                .as("the startup report the operator reads the selected mode from")
                .hasSize(1);
        assertThat(context.getBeanNamesForType(ContestScoreboardFullReplayStartupRunner.class))
                .as("the bean selecting this mode is supposed to add")
                .hasSize(1);
        assertThat(context.getBeanNamesForType(ContestScoreboardFullReplayService.class))
                .as("the replay itself, which the retention-gap fallback also uses")
                .hasSize(1);
        // Neither of the other modes' exclusive beans may come up alongside this one.
        assertThat(context.getBeanNamesForType(ContestScoreboardMySqlPollLifecycle.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ContestScoreboardRedisSequenceMetrics.class)).isEmpty();
        assertThat(ContestScoreboardRecoverySummary.describe(properties.mode(), "memory", properties))
                .startsWith("mode=full-replay store=memory");
    }
}
