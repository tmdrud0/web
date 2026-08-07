package my.oj.web.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the poller against a stubbed JdbcTemplate. The SQL itself is MySQL-specific - derived
 * table LIMIT, TIMESTAMPDIFF, the index hints implied by the ORDER BY - so what is verified here
 * is the gauge contract around it: what reaches the gauges, what happens to a status that
 * disappears, and what happens when the database does not answer.
 */
class ContestOutboxBacklogMetricsTests {

    private static final int MAX_COUNTED_ROWS = 25_000;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ContestOutboxBacklogMetrics metrics;

    @BeforeEach
    void bindAgainstAnEmptyOutbox() {
        stubBacklog("contest_judge_outbox", Map.of());
        stubBacklog("contest_submission_outbox", Map.of());
        stubHeadLag("contest_judge_outbox", List.of());
        stubHeadLag("contest_submission_outbox", List.of());
        metrics = new ContestOutboxBacklogMetrics(
                jdbcTemplate, new ContestOutboxMetricsProperties(MAX_COUNTED_ROWS));
        metrics.bindTo(registry);
    }

    @Test
    void publishesEachNonTerminalStatusOfBothOutboxes() {
        stubBacklog("contest_judge_outbox", Map.of("PENDING", 120L, "PUBLISHING", 8L));
        stubBacklog("contest_submission_outbox", Map.of("PENDING", 40L, "PROCESSING", 6L, "FAILED", 2L));

        metrics.poll();

        assertThat(backlog("judge", "PENDING")).isEqualTo(120.0);
        assertThat(backlog("judge", "PUBLISHING")).isEqualTo(8.0);
        assertThat(backlog("scoreboard", "PENDING")).isEqualTo(40.0);
        assertThat(backlog("scoreboard", "PROCESSING")).isEqualTo(6.0);
        assertThat(backlog("scoreboard", "FAILED")).isEqualTo(2.0);
    }

    /**
     * The terminal statuses have no purge policy, so counting them would tie the query cost to
     * table size instead of backlog size.
     */
    @Test
    void countsOnlyTheStatusesThatAreStillWaiting() {
        metrics.poll();

        assertThat(registry.find("contest.outbox.backlog").tag("status", "PUBLISHED").gauge()).isNull();
        assertThat(registry.find("contest.outbox.backlog").tag("status", "COMPLETED").gauge()).isNull();
    }

    @Test
    void capsTheScanAtTheConfiguredLimit() {
        metrics.poll();

        // The cap is the query's LIMIT, so an overrunning backlog costs a fixed scan.
        verify(jdbcTemplate).query(contains("contest_judge_outbox"), any(RowMapper.class), eq(MAX_COUNTED_ROWS));
        verify(jdbcTemplate).query(contains("contest_submission_outbox"), any(RowMapper.class), eq(MAX_COUNTED_ROWS));
    }

    /** A status that drains to nothing returns no row at all rather than a row holding zero. */
    @Test
    void clearsAStatusThatNoLongerReturnsARow() {
        stubBacklog("contest_judge_outbox", Map.of("PENDING", 500L, "PUBLISHING", 3L));
        metrics.poll();
        assertThat(backlog("judge", "PUBLISHING")).isEqualTo(3.0);

        stubBacklog("contest_judge_outbox", Map.of("PENDING", 500L));
        metrics.poll();

        assertThat(backlog("judge", "PUBLISHING")).isZero();
        assertThat(backlog("judge", "PENDING")).isEqualTo(500.0);
    }

    @Test
    void reportsHeadOfLineLagInSeconds() {
        stubHeadLag("contest_submission_outbox", List.of(4_250_000L));

        metrics.poll();

        assertThat(headLag("scoreboard")).isEqualTo(4.25);
    }

    /**
     * A FAILED row waiting out its backoff, and a PROCESSING row inside its lease, both sit ahead
     * of now. Nothing is overdue in either case.
     */
    @Test
    void clampsARowThatIsNotDueYetToZero() {
        stubHeadLag("contest_submission_outbox", List.of(-30_000_000L));

        metrics.poll();

        assertThat(headLag("scoreboard")).isZero();
    }

    @Test
    void reportsNoLagWhenTheOutboxIsEmpty() {
        stubHeadLag("contest_judge_outbox", List.of(9_000_000L));
        metrics.poll();
        assertThat(headLag("judge")).isEqualTo(9.0);

        stubHeadLag("contest_judge_outbox", List.of());
        metrics.poll();

        assertThat(headLag("judge")).isZero();
    }

    /**
     * A gauge that fell to zero here would read as "the backlog cleared", which is the opposite
     * of what a failing query means. The counter is what separates the two.
     */
    @Test
    void keepsThePreviousValuesWhenTheQueryFails() {
        stubBacklog("contest_judge_outbox", Map.of("PENDING", 900L));
        stubHeadLag("contest_judge_outbox", List.of(12_000_000L));
        metrics.poll();

        when(jdbcTemplate.query(contains("contest_judge_outbox"), any(RowMapper.class), anyInt()))
                .thenThrow(new QueryTimeoutException("statement cancelled"));
        metrics.poll();

        assertThat(backlog("judge", "PENDING")).isEqualTo(900.0);
        assertThat(headLag("judge")).isEqualTo(12.0);
        assertThat(registry.get("contest.outbox.backlog.poll.failures").counter().count()).isEqualTo(1.0);
    }

    /** One outbox failing must not take the other's reading down with it. */
    @Test
    void keepsReadingTheOtherOutboxAfterOneFails() {
        when(jdbcTemplate.query(contains("contest_judge_outbox"), any(RowMapper.class), anyInt()))
                .thenThrow(new QueryTimeoutException("statement cancelled"));
        stubBacklog("contest_submission_outbox", Map.of("PENDING", 77L));

        metrics.poll();

        assertThat(backlog("scoreboard", "PENDING")).isEqualTo(77.0);
    }

    /**
     * The Micrometer meter id and the exported series name are not the same string, and the
     * dashboard and the alert rules query the exported one.
     */
    @Test
    void exportsTheSeriesNamesTheRulesQuery() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new ContestOutboxBacklogMetrics(jdbcTemplate, new ContestOutboxMetricsProperties(MAX_COUNTED_ROWS))
                .bindTo(prometheus);

        assertThat(prometheus.scrape())
                .contains("contest_outbox_backlog_rows")
                .contains("contest_outbox_head_lag_seconds")
                .contains("outbox=\"judge\"")
                .contains("outbox=\"scoreboard\"");
    }

    private double backlog(String outbox, String status) {
        return registry.get("contest.outbox.backlog")
                .tags("outbox", outbox, "status", status)
                .gauge().value();
    }

    private double headLag(String outbox) {
        return registry.get("contest.outbox.head.lag").tag("outbox", outbox).gauge().value();
    }

    @SuppressWarnings("unchecked")
    private void stubBacklog(String table, Map<String, Long> countsByStatus) {
        when(jdbcTemplate.query(contains(table), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    List<Object> rows = new ArrayList<>();
                    for (Map.Entry<String, Long> entry : countsByStatus.entrySet()) {
                        rows.add(mapper.mapRow(statusRow(entry.getKey(), entry.getValue()), rows.size()));
                    }
                    return rows;
                });
    }

    private void stubHeadLag(String table, List<Long> micros) {
        when(jdbcTemplate.queryForList(contains(table), eq(Long.class))).thenReturn(micros);
    }

    private static ResultSet statusRow(String status, long rowCount) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("status")).thenReturn(status);
        when(resultSet.getLong("row_count")).thenReturn(rowCount);
        return resultSet;
    }
}
