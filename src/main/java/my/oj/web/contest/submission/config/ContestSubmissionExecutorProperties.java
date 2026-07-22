package my.oj.web.contest.submission.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("contest.submission.async")
public record ContestSubmissionExecutorProperties(
        @DefaultValue("2") int corePoolSize,
        @DefaultValue("4") int maxPoolSize,
        @DefaultValue("1000") int queueCapacity
) {

    public int effectiveCorePoolSize() {
        return Math.max(1, corePoolSize);
    }

    public int effectiveMaxPoolSize() {
        return Math.max(corePoolSize, maxPoolSize);
    }

    public int effectiveQueueCapacity() {
        return Math.max(1, queueCapacity);
    }
}
