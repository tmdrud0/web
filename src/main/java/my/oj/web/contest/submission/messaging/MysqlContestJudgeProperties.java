package my.oj.web.contest.submission.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("contest.submission.judge.mysql")
public record MysqlContestJudgeProperties(
        @DefaultValue("16") int workerCount,
        @DefaultValue("32") int claimBatchSize,
        @DefaultValue("128") int maxInFlight,
        @DefaultValue("30s") Duration claimTimeout,
        @DefaultValue("100ms") Duration pollInterval
) {
    public int effectiveWorkerCount() {
        return Math.max(1, workerCount);
    }

    public int effectiveClaimBatchSize() {
        return Math.max(1, claimBatchSize);
    }

    public int effectiveMaxInFlight() {
        return Math.max(1, maxInFlight);
    }

    public Duration effectiveClaimTimeout() {
        return claimTimeout == null || claimTimeout.isNegative() ? Duration.ZERO : claimTimeout;
    }
}
