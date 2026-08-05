package my.oj.web.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes how many rows are waiting in each outbox and how long the row at the head of the
 * queue has been waiting.
 *
 * <p>These are the two positions in the pipeline history section 7 table that hold load in MySQL
 * with no ceiling. Every other queue in that table is bounded - by a semaphore, by a fixed
 * executor queue, or by the broker - so a run that overruns capacity ends up here, and until now
 * nothing reported it.
 *
 * <p><strong>Off the scrape path.</strong> The queries run on a scheduler and the gauges read the
 * result they left behind. Prometheus scrapes at 5s with a 4s timeout, and a scrape that overruns
 * that drops every metric for the instance, not just this one - so no DB round trip may sit
 * between a scrape and its response.
 *
 * <p><strong>Bounded queries.</strong> The counting query is capped by a LIMIT inside a derived
 * table, so its cost does not grow with the backlog it exists to measure. Neither outbox has a
 * purge policy yet (pipeline history section 9.4), so the terminal rows - PUBLISHED and COMPLETED
 * - accumulate forever; every query here is restricted to the non-terminal statuses so that it
 * scans the backlog rather than the table. A saturated gauge reports the cap. That is well above
 * any alert threshold, so saturation cannot hide an alert - it can only understate a backlog that
 * is already firing one.
 *
 * <p>Published from a single role. Five instances polling would multiply the query load by five
 * and produce five identical series, where summing across instances - the correct operation for
 * every other application metric here - would give five times the real backlog.
 */
@Component
@ConditionalOnProperty(prefix = "contest.outbox.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContestOutboxBacklogMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ContestOutboxBacklogMetrics.class);

    /**
     * Counting is an index-only range scan over the non-terminal statuses: PENDING sorts before
     * PUBLISHING in {@code idx_contest_judge_outbox_claim (status, claimed_at, id)}, and the
     * derived table's LIMIT stops the scan.
     */
    private static final String JUDGE_BACKLOG_SQL = """
            SELECT status, COUNT(*) AS row_count
            FROM (SELECT status
                  FROM contest_judge_outbox
                  WHERE status IN ('PENDING', 'PUBLISHING')
                  LIMIT ?) capped
            GROUP BY status
            """;

    /**
     * A PENDING row always has a NULL {@code claimed_at} - claiming sets both columns together and
     * the failure path clears both - so within {@code status = 'PENDING'} the claim index is
     * ordered by id alone and its first entry is the oldest row. That makes this one index entry
     * and one row lookup regardless of backlog depth.
     *
     * <p>A row that is stuck in PUBLISHING is not reached by this: it shows up in the backlog
     * count, not in the age.
     *
     * <p>The subtraction happens in MySQL so the age never mixes two clocks.
     */
    private static final String JUDGE_HEAD_LAG_SQL = """
            SELECT TIMESTAMPDIFF(MICROSECOND, created_at, CURRENT_TIMESTAMP(6))
            FROM contest_judge_outbox
            WHERE status = 'PENDING'
            ORDER BY claimed_at, id
            LIMIT 1
            """;

    private static final String SCOREBOARD_BACKLOG_SQL = """
            SELECT status, COUNT(*) AS row_count
            FROM (SELECT status
                  FROM contest_submission_outbox
                  WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')
                  LIMIT ?) capped
            GROUP BY status
            """;

    /**
     * {@code due_at} is the single column the worker claims on (V15), and it is NULL exactly for
     * COMPLETED rows, so {@code idx_cs_outbox_due (due_at, id)} hands over the head of the queue
     * in one index entry. Reading the same column the claim query orders by means this measures
     * the delay the worker will actually see next.
     *
     * <p>Negative for a row whose retry backoff has not elapsed and for a PROCESSING row inside
     * its lease. Both mean nothing is overdue, and the caller clamps them to zero.
     */
    private static final String SCOREBOARD_HEAD_LAG_SQL = """
            SELECT TIMESTAMPDIFF(MICROSECOND, due_at, CURRENT_TIMESTAMP(6))
            FROM contest_submission_outbox
            WHERE due_at IS NOT NULL
            ORDER BY due_at, id
            LIMIT 1
            """;

    private static final Map<String, List<String>> STATUSES = Map.of(
            ContestOutboxDrainMetrics.JUDGE_OUTBOX, List.of("PENDING", "PUBLISHING"),
            ContestOutboxDrainMetrics.SCOREBOARD_OUTBOX, List.of("PENDING", "PROCESSING", "FAILED")
    );

    private final JdbcTemplate jdbcTemplate;
    private final int maxCountedRows;
    private final Map<BacklogKey, AtomicLong> backlogRows = new LinkedHashMap<>();
    private final Map<String, AtomicLong> headLagMicros = new LinkedHashMap<>();

    /**
     * A composite registry with no children discards every recording, so the counter is usable
     * before Spring binds this to the real registry.
     */
    private volatile Counter pollFailures = pollFailureCounter(new CompositeMeterRegistry());

    public ContestOutboxBacklogMetrics(JdbcTemplate jdbcTemplate, ContestOutboxMetricsProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxCountedRows = properties.effectiveMaxCountedRows();
        STATUSES.forEach((outbox, statuses) -> {
            statuses.forEach(status -> backlogRows.put(new BacklogKey(outbox, status), new AtomicLong()));
            headLagMicros.put(outbox, new AtomicLong());
        });
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        backlogRows.forEach((key, rows) -> Gauge.builder("contest.outbox.backlog", rows, AtomicLong::get)
                .tags("outbox", key.outbox(), "status", key.status())
                .baseUnit("rows")
                .description("Rows waiting in this outbox, capped at the configured scan "
                        + "limit. Terminal rows are not counted: they are not backlog and "
                        + "counting them would make the query grow with table size")
                .register(registry));
        headLagMicros.forEach((outbox, micros) -> Gauge.builder(
                        "contest.outbox.head.lag", micros, value -> value.get() / 1_000_000.0)
                .tag("outbox", outbox)
                .baseUnit("seconds")
                .description("How long the row at the head of this outbox has been claimable "
                        + "without being claimed. Unlike a drain-rate estimate this still moves "
                        + "when one row is stuck behind a backlog that is otherwise draining")
                .register(registry));
        this.pollFailures = pollFailureCounter(registry);
    }

    private static Counter pollFailureCounter(MeterRegistry registry) {
        return Counter.builder("contest.outbox.backlog.poll.failures")
                .description("Backlog queries that failed. The gauges hold their last value "
                        + "rather than falling to zero, so a flat backlog next to a rising count "
                        + "here means the poller stopped looking, not that the backlog cleared")
                .register(registry);
    }

    /**
     * Runs on the shared scheduling pool, alongside the relay and the scoreboard worker. Both
     * queries are index-only and bounded, but the pool defaults to a single thread outside
     * batch-role, where a long query would delay the drains this metric is measuring.
     */
    @Scheduled(fixedDelayString = "${contest.outbox.metrics.poll-interval-ms:5000}")
    public void poll() {
        readBacklog(ContestOutboxDrainMetrics.JUDGE_OUTBOX, JUDGE_BACKLOG_SQL, JUDGE_HEAD_LAG_SQL);
        readBacklog(ContestOutboxDrainMetrics.SCOREBOARD_OUTBOX, SCOREBOARD_BACKLOG_SQL, SCOREBOARD_HEAD_LAG_SQL);
    }

    private void readBacklog(String outbox, String backlogSql, String headLagSql) {
        try {
            List<StatusCount> counts = jdbcTemplate.query(backlogSql,
                    (resultSet, rowNum) -> new StatusCount(
                            resultSet.getString("status"), resultSet.getLong("row_count")),
                    maxCountedRows);
            // Applied only after both queries succeed, so a partial read never publishes a zero
            // for a status that simply was not reached.
            List<Long> headLag = jdbcTemplate.queryForList(headLagSql, Long.class);

            Map<String, Long> byStatus = new LinkedHashMap<>();
            counts.forEach(count -> byStatus.put(count.status(), count.rows()));
            // A status that has drained to nothing returns no row at all, so every status is
            // written on every poll. Leaving one untouched would pin it at its last value.
            STATUSES.get(outbox).forEach(status ->
                    backlogRows.get(new BacklogKey(outbox, status)).set(byStatus.getOrDefault(status, 0L)));
            // Empty means an empty outbox, which is a lag of zero rather than an unknown one.
            Long lagMicros = headLag.isEmpty() ? null : headLag.get(0);
            headLagMicros.get(outbox).set(lagMicros == null ? 0L : Math.max(0L, lagMicros));
        } catch (RuntimeException e) {
            // Wider than DataAccessException on purpose. Anything that stops the poll leaves the
            // gauges stale, and the alert that says so reads this counter - so every way of
            // failing has to reach it, not only the ones the JDBC layer translates.
            pollFailures.increment();
            log.warn("Failed to read {} outbox backlog; gauges keep their previous values", outbox, e);
        }
    }

    private record BacklogKey(String outbox, String status) {
    }

    private record StatusCount(String status, long rows) {
    }
}
