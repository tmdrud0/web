package my.oj.web.contest.scoreboard.recovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import my.oj.web.contest.scoreboard.PositiveDuration;
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
     * <p>This is an execution boundary, not a declaration. Each of the three mode triggers carries
     * {@link ContestScoreboardRecoveryOwnerCondition}, so an instance with {@code owner.enabled=false}
     * has no startup replay, no startup sequence check and no sequence-check intervals registered at
     * all - they are absent as beans rather than present and declining. Nothing else in the
     * application needs to read the setting to make that true.</p>
     *
     * <p>What the setting does not do is coordinate instances. The gates and locks in this package are
     * JVM-local, so two instances that both declare themselves owners both run, and the deployment's
     * single {@code batch-role} instance remains the only thing that prevents it. The setting narrows
     * who may recover; it cannot detect a second owner. {@link ContestScoreboardRecoveryValidator}
     * stops the configurations that would make the declaration false in the other direction.</p>
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

    /**
     * Settings of the removed Stream-driven {@code redis-seq} checks. Still bound so that an existing
     * configuration keeps starting, but <strong>nothing reads them</strong>: {@code redis-seq} now runs on
     * the {@code mysql-poll} delivery and its settings are {@code contest.scoreboard.mysql-poll.*}.
     *
     * <p>Detecting a reused sequence number and a lost tail.
     *
     * <p>The two check intervals are handed to the scheduler as they are, with no clamp, and the
     * retry backoff bounds how long a failed replay waits before offering the same chunk again. A
     * non-positive value in any of them is a cadence that cannot be delivered, so it is refused at
     * startup rather than normalised into something the operator did not ask for.</p>
     */
    public record RedisSequence(
            @DefaultValue("30s") @PositiveDuration Duration duplicateCheckInterval,
            @DefaultValue("30s") @PositiveDuration Duration lostTailCheckInterval,
            @DefaultValue("1000") @Min(1) int checkWindowSize,
            @DefaultValue("10") @Min(1) int maxWindowsPerPass,
            @DefaultValue("5") @Min(1) int maxIterations,
            @DefaultValue("500") @Min(1) int replayBatchSize,
            @DefaultValue("3") @Min(1) int retryMaxAttempts,
            @DefaultValue("50ms") @PositiveDuration Duration retryBackoff,
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
