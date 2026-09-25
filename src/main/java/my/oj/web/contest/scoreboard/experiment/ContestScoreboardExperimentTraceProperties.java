package my.oj.web.contest.scoreboard.experiment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import my.oj.web.contest.scoreboard.PositiveDuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of the live-impact experiment's trace. Disabled unless a run turns it on.
 *
 * @param enabled       whether any record is kept. False everywhere except an experiment stack
 * @param directory     where {@code live-apply.csv} and {@code recovery-trace.csv} are written
 * @param queueCapacity how many records may wait for the writer before new ones are dropped and
 *                      counted. A live batch is one record however many events it carries
 * @param flushInterval how often the writer thread drains the queue to disk
 */
@ConfigurationProperties("contest.scoreboard.experiment.trace")
@Validated
public record ContestScoreboardExperimentTraceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("/tmp/scoreboard-experiment-trace") @NotBlank String directory,
        @DefaultValue("65536") @Min(1) int queueCapacity,
        @DefaultValue("200ms") @PositiveDuration Duration flushInterval
) {
}
