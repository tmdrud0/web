package my.oj.web.contest.submission.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("contest.submission.judge.rabbit.publisher")
public record ContestJudgeOutboxRelayProperties(
        @DefaultValue("50") int batchSize,
        @DefaultValue("30s") Duration claimTimeout,
        @DefaultValue("10s") Duration confirmTimeout
) {

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }
}
