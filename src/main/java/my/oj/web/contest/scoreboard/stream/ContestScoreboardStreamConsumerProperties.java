package my.oj.web.contest.scoreboard.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("contest.scoreboard.stream.consumer")
public record ContestScoreboardStreamConsumerProperties(
        @DefaultValue("500") int batchSize,
        @DefaultValue("500") int prefetch,
        @DefaultValue("50ms") Duration receiveTimeout,
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue("1s") Duration offsetCheckInterval,
        @DefaultValue("5s") Duration tailProbeInterval,
        @DefaultValue("50ms") Duration tailProbeQuietPeriod,
        @DefaultValue("2s") Duration tailProbeTimeout,
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
