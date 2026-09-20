package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.PositiveDuration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the scoreboard stream consumer reads the broker, and how it watches its own position.
 *
 * <p>Every duration here is validated rather than clamped. The batch sizes below keep their
 * {@code Math.max} helpers, because a prefetch below the batch size is a tuning slip that has an
 * obvious safe meaning; a period or a timeout of zero has none, and the consumer that ran on some
 * other value than the operator configured would be a schedule nobody could reason about. The floor
 * is a millisecond, not a second: several of these are sub-second by default - the receive timeout is
 * 50ms - and the integration tests configure smaller ones still.</p>
 */
@ConfigurationProperties("contest.scoreboard.stream.consumer")
@Validated
public record ContestScoreboardStreamConsumerProperties(
        @DefaultValue("500") int batchSize,
        @DefaultValue("500") int prefetch,
        @DefaultValue("50ms") @PositiveDuration Duration receiveTimeout,
        @DefaultValue("1s") @PositiveDuration Duration retryBackoff,
        @DefaultValue("1s") @PositiveDuration Duration offsetCheckInterval,
        @DefaultValue("5s") @PositiveDuration Duration tailProbeInterval,
        @DefaultValue("50ms") @PositiveDuration Duration tailProbeQuietPeriod,
        @DefaultValue("2s") @PositiveDuration Duration tailProbeTimeout,
        @DefaultValue("4096") int tailProbePrefetch
) {

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectivePrefetch() {
        return Math.max(effectiveBatchSize(), prefetch);
    }

    public long effectiveReceiveTimeoutMillis() {
        return Math.max(1L, receiveTimeout.toMillis());
    }

    public int effectiveTailProbePrefetch() {
        return Math.max(1, tailProbePrefetch);
    }
}
