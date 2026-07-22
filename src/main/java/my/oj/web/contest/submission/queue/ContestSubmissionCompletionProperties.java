package my.oj.web.contest.submission.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("contest.submission.completion")
public record ContestSubmissionCompletionProperties(
        @DefaultValue("8") int threadCount,
        @DefaultValue("256") int queueCapacity
) {

    public int effectiveThreadCount() {
        return Math.max(1, threadCount);
    }

    public int effectiveQueueCapacity() {
        return Math.max(1, queueCapacity);
    }
}
