package my.oj.web.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxCountedRows how far the backlog count is allowed to scan. Set this above every alert
 *                       threshold: a saturated gauge understates the backlog, and it must never
 *                       drop one below a threshold it had already crossed.
 */
@ConfigurationProperties("contest.outbox.metrics")
public record ContestOutboxMetricsProperties(
        @DefaultValue("100000") int maxCountedRows
) {

    public int effectiveMaxCountedRows() {
        return Math.max(1, maxCountedRows);
    }
}
