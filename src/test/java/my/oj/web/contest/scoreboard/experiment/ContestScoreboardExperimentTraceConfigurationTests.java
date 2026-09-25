package my.oj.web.contest.scoreboard.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Off unless asked for. A context that does not set the property has no trace bean at all, so every
 * observed component falls back to {@link ContestScoreboardExperimentTrace#NOOP} and wires as it did
 * before the trace existed.
 */
class ContestScoreboardExperimentTraceConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ContestScoreboardExperimentTraceConfiguration.class);

    @TempDir
    Path directory;

    @Test
    void noTraceExistsUnlessTheExperimentTurnsItOn() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ContestScoreboardExperimentTrace.class);
            assertThat(context.getBean(ContestScoreboardExperimentTraceProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void anEnabledTraceWritesToTheConfiguredDirectory() {
        runner.withPropertyValues(
                        "contest.scoreboard.experiment.trace.enabled=true",
                        "contest.scoreboard.experiment.trace.directory=" + directory)
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestScoreboardExperimentTrace.class);
                    assertThat(context.getBean(ContestScoreboardExperimentTrace.class).enabled()).isTrue();
                    assertThat(Files.exists(directory.resolve(AsyncCsvContestScoreboardExperimentTrace.LIVE_FILE))).isTrue();
                });
    }
}
