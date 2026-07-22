package my.oj.web.contest.submission.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("contest.submission.judge.result-writer")
public record ContestSubmissionJudgeResultWriterProperties(
        @DefaultValue("16") int batchSize,
        @DefaultValue("1") int workerCount,
        @DefaultValue("256") int queueCapacity,
        @DefaultValue("5ms") Duration maxWait
) {

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveWorkerCount() {
        return Math.max(1, workerCount);
    }

    public int effectiveQueueCapacity() {
        return Math.max(effectiveBatchSize(), queueCapacity);
    }

    public long effectiveMaxWaitNanos() {
        return Math.max(0L, maxWait.toNanos());
    }
}
