package my.oj.web.contest.scoreboard.recovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Everything the recovery modes expose to operators.
 *
 * <p>Values here are validated rather than clamped: the other scoreboard properties normalise with
 * {@code Math.max} helpers, but a nonsensical recovery setting is a configuration mistake that
 * should stop the JVM instead of quietly running a weaker check than the operator asked for.</p>
 *
 * <p>The nested records carry {@code @Valid} so their constraints are cascaded into; without it
 * the bounds below would bind happily and never be checked.</p>
 */
@ConfigurationProperties("contest.scoreboard.recovery")
@Validated
public record ContestScoreboardRecoveryProperties(
        @DefaultValue("stream-offset") ContestScoreboardRecoveryMode mode,
        @Valid @DefaultValue FullReplay fullReplay,
        @Valid @DefaultValue RedisSequence redisSeq,
        @Valid @DefaultValue StreamOffset streamOffset,
        @Valid @DefaultValue RecoveryOwner owner
) {

    /**
     * Whether this instance is the one that runs recovery passes.
     *
     * <p>Nothing enforces this across instances, and nothing can: the gates and locks in this package
     * are JVM-local, so two instances that both run a pass will both run it. What the setting does is
     * make the deployment's assumption explicit and checkable at startup, so a role that silently
     * became a second recovery owner is stopped rather than discovered later - see
     * {@link ContestScoreboardRecoveryValidator}.</p>
     */
    public record RecoveryOwner(@DefaultValue("true") boolean enabled) {
    }

    /** Replaying the whole contest from MySQL. */
    public record FullReplay(
            @DefaultValue("1000") @Min(1) int dbBatchSize,
            @DefaultValue("500") @Min(1) int replayBatchSize,
            @DefaultValue("true") boolean startupReplayEnabled
    ) {
    }

    /** Detecting a reused sequence number and a lost tail. */
    public record RedisSequence(
            @DefaultValue("30s") Duration duplicateCheckInterval,
            @DefaultValue("30s") Duration lostTailCheckInterval,
            @DefaultValue("1000") @Min(1) int checkWindowSize,
            @DefaultValue("10") @Min(1) int maxWindowsPerPass,
            @DefaultValue("5") @Min(1) int maxIterations,
            @DefaultValue("500") @Min(1) int replayBatchSize,
            @DefaultValue("3") @Min(1) int retryMaxAttempts,
            @DefaultValue("50ms") Duration retryBackoff,
            @DefaultValue("true") boolean startupCheckEnabled
    ) {
    }

    /** The existing mechanism: the offset stored with the scoreboard. */
    public record StreamOffset(
            @DefaultValue("full-replay") RetentionGapFallback retentionGapFallback,
            @DefaultValue("stored") StartupOffset startupOffset
    ) {

        /** What to do when the offset the scoreboard needs is no longer retained by the broker. */
        public enum RetentionGapFallback {
            FULL_REPLAY("full-replay"),
            NONE("none");

            private final String propertyValue;

            RetentionGapFallback(String propertyValue) {
                this.propertyValue = propertyValue;
            }

            public String propertyValue() {
                return propertyValue;
            }
        }

        /** Where a restarted consumer begins. */
        public enum StartupOffset {
            STORED("stored"),
            FIRST("first");

            private final String propertyValue;

            StartupOffset(String propertyValue) {
                this.propertyValue = propertyValue;
            }

            public String propertyValue() {
                return propertyValue;
            }
        }
    }
}
