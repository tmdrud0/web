package my.oj.web.contest.submission.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("contest.submission.bulk")
public record ContestSubmissionBulkProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("1") int workerCount
) {

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveWorkerCount() {
        return Math.max(1, workerCount);
    }
}
