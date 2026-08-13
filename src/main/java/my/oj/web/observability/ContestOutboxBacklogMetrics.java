package my.oj.web.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** MySQL backlog diagnostics for the remaining judge outbox only. */
@Component
@ConditionalOnProperty(prefix = "contest.outbox.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ContestOutboxBacklogMetrics implements MeterBinder {

    private static final String BACKLOG_SQL = """
            SELECT status, COUNT(*) AS row_count
            FROM (SELECT status
                  FROM contest_judge_outbox
                  WHERE status IN ('PENDING', 'PUBLISHING')
                  LIMIT ?) capped
            GROUP BY status
            """;

    private static final String HEAD_LAG_SQL = """
            SELECT TIMESTAMPDIFF(MICROSECOND, created_at, CURRENT_TIMESTAMP(6))
            FROM contest_judge_outbox
            WHERE status = 'PENDING'
            ORDER BY claimed_at, id
            LIMIT 1
            """;

    private static final List<String> STATUSES = List.of("PENDING", "PUBLISHING");

    private final JdbcTemplate jdbcTemplate;
    private final int maxCountedRows;
    private final Map<String, AtomicLong> backlogRows = new LinkedHashMap<>();
    private final AtomicLong headLagMicros = new AtomicLong();
    private volatile Counter pollFailures = pollFailureCounter(new CompositeMeterRegistry());

    public ContestOutboxBacklogMetrics(JdbcTemplate jdbcTemplate, ContestOutboxMetricsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxCountedRows = properties.effectiveMaxCountedRows();
        STATUSES.forEach(status -> backlogRows.put(status, new AtomicLong()));
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        backlogRows.forEach((status, rows) -> Gauge.builder("contest.outbox.backlog", rows, AtomicLong::get)
                .tags("outbox", ContestOutboxDrainMetrics.JUDGE_OUTBOX, "status", status)
                .baseUnit("rows")
                .description("Rows waiting in the judge outbox, capped at the configured scan limit")
                .register(registry));
        Gauge.builder("contest.outbox.head.lag", headLagMicros, value -> value.get() / 1_000_000.0)
                .tag("outbox", ContestOutboxDrainMetrics.JUDGE_OUTBOX)
                .baseUnit("seconds")
                .description("How long the oldest PENDING judge outbox row has been waiting")
                .register(registry);
        this.pollFailures = pollFailureCounter(registry);
    }

    private static Counter pollFailureCounter(MeterRegistry registry) {
        return Counter.builder("contest.outbox.backlog.poll.failures")
                .description("Judge outbox backlog queries that failed; gauges keep their previous value")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${contest.outbox.metrics.poll-interval-ms:5000}")
    public void poll() {
        try {
            List<StatusCount> counts = jdbcTemplate.query(
                    BACKLOG_SQL,
                    (resultSet, rowNum) -> new StatusCount(
                            resultSet.getString("status"),
                            resultSet.getLong("row_count")
                    ),
                    maxCountedRows
            );
            List<Long> headLag = jdbcTemplate.queryForList(HEAD_LAG_SQL, Long.class);
            Map<String, Long> byStatus = new LinkedHashMap<>();
            counts.forEach(count -> byStatus.put(count.status(), count.rows()));
            STATUSES.forEach(status -> backlogRows.get(status).set(byStatus.getOrDefault(status, 0L)));
            Long micros = headLag.isEmpty() ? null : headLag.get(0);
            headLagMicros.set(micros == null ? 0L : Math.max(0L, micros));
        } catch (RuntimeException failure) {
            pollFailures.increment();
            log.warn("Failed to read judge outbox backlog; gauges keep their previous values", failure);
        }
    }

    private record StatusCount(String status, long rows) {
    }
}
