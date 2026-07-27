package my.oj.web.contest.scoreboard.outbox.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("contest.outbox")
public record ContestScoreboardOutboxProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("50") int recoveryBatchSize,
        @DefaultValue("30s") Duration claimTimeout
) {

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveRecoveryBatchSize() {
        return Math.max(1, recoveryBatchSize);
    }
}
