package my.oj.web.contest.scoreboard.experiment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Creates the experiment trace only when a run asks for it.
 *
 * <p>No bean exists otherwise, and every observed component resolves the trace through an
 * {@code ObjectProvider} that falls back to {@link ContestScoreboardExperimentTrace#NOOP}. A context
 * without this property therefore wires exactly the components it wired before the trace existed.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContestScoreboardExperimentTraceProperties.class)
public class ContestScoreboardExperimentTraceConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "contest.scoreboard.experiment.trace", name = "enabled", havingValue = "true")
    AsyncCsvContestScoreboardExperimentTrace contestScoreboardExperimentTrace(
            ContestScoreboardExperimentTraceProperties properties) {
        return new AsyncCsvContestScoreboardExperimentTrace(
                Path.of(properties.directory()),
                properties.queueCapacity(),
                properties.flushInterval()
        );
    }
}
