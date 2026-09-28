package my.oj.web.contest.scoreboard.poll;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import my.oj.web.contest.scoreboard.PositiveDuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings of the {@code mysql-poll} delivery. Validated rather than clamped, like the recovery
 * properties: a cadence or size that cannot be delivered is a configuration mistake.
 *
 * @param batchSize             judged rows read and applied per poll batch
 * @param pollInterval          delay between the end of one poll and the start of the next
 * @param rollbackCheckInterval delay between rollback checks that run even when nothing is judged
 * @param recoveryInterval      delay between passes over the pending recovery ranges
 * @param recoveryChunkSize     rows re-applied per chunk of a range recovery, one apply-lock hold each
 * @param recoveryMaxIterations chunks one pass may spend on one range before leaving it pending
 * @param ownershipLockName     MySQL {@code GET_LOCK} name that makes a second poller refuse to run
 */
@ConfigurationProperties("contest.scoreboard.mysql-poll")
@Validated
public record ContestScoreboardMySqlPollProperties(
        @DefaultValue("500") @Min(1) int batchSize,
        @DefaultValue("200ms") @PositiveDuration Duration pollInterval,
        @DefaultValue("5s") @PositiveDuration Duration rollbackCheckInterval,
        @DefaultValue("1s") @PositiveDuration Duration recoveryInterval,
        @DefaultValue("500") @Min(1) int recoveryChunkSize,
        @DefaultValue("1000") @Min(1) int recoveryMaxIterations,
        @DefaultValue("oj.contest.scoreboard.mysql-poll") @NotBlank String ownershipLockName
) {
}
