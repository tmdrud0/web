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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestOutboxBacklogMetricsTests {

    private static final int MAX_COUNTED_ROWS = 25_000;
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ContestOutboxBacklogMetrics metrics;

    @BeforeEach
    void setUp() {
        stubBacklog(List.of());
        when(jdbcTemplate.queryForList(contains("contest_judge_outbox"), eq(Long.class)))
                .thenReturn(List.of());
        metrics = new ContestOutboxBacklogMetrics(
                jdbcTemplate, new ContestOutboxMetricsProperties(MAX_COUNTED_ROWS));
        metrics.bindTo(registry);
    }

    @Test
    void publishesOnlyTheRemainingJudgeOutboxStatuses() throws Exception {
        ResultSet pending = row("PENDING", 120L);
        ResultSet publishing = row("PUBLISHING", 8L);
        when(jdbcTemplate.query(contains("contest_judge_outbox"), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(pending, 0), mapper.mapRow(publishing, 1));
                });

        metrics.poll();

        assertThat(backlog("PENDING")).isEqualTo(120.0);
        assertThat(backlog("PUBLISHING")).isEqualTo(8.0);
        assertThat(registry.find("contest.outbox.backlog").tag("outbox", "scoreboard").gauge()).isNull();
        assertThat(registry.find("contest.scoreboard.pending").gauge()).isNull();
    }

    @Test
    void usesConfiguredCapAndReportsJudgeHeadLag() {
        when(jdbcTemplate.queryForList(contains("contest_judge_outbox"), eq(Long.class)))
                .thenReturn(List.of(4_250_000L));

        metrics.poll();

        verify(jdbcTemplate).query(contains("contest_judge_outbox"), any(RowMapper.class), eq(MAX_COUNTED_ROWS));
        assertThat(registry.get("contest.outbox.head.lag").tag("outbox", "judge").gauge().value())
                .isEqualTo(4.25);
    }

    @Test
    void keepsPreviousValuesAndCountsPollFailure() throws Exception {
        ResultSet pending = row("PENDING", 9L);
        when(jdbcTemplate.query(contains("contest_judge_outbox"), any(RowMapper.class), anyInt()))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<?>) invocation.getArgument(1)).mapRow(pending, 0)));
        metrics.poll();
        when(jdbcTemplate.query(contains("contest_judge_outbox"), any(RowMapper.class), anyInt()))
                .thenThrow(new QueryTimeoutException("timeout"));

        metrics.poll();

        assertThat(backlog("PENDING")).isEqualTo(9.0);
        assertThat(registry.get("contest.outbox.backlog.poll.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void exportsJudgeOutboxSeriesButNoScoreboardOutboxSeries() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new ContestOutboxBacklogMetrics(jdbcTemplate, new ContestOutboxMetricsProperties(MAX_COUNTED_ROWS))
                .bindTo(prometheus);

        assertThat(prometheus.scrape())
                .contains("contest_outbox_backlog_rows")
                .contains("contest_outbox_head_lag_seconds")
                .contains("outbox=\"judge\"")
                .doesNotContain("outbox=\"scoreboard\"")
                .doesNotContain("contest_scoreboard_pending_events");
    }

    private double backlog(String status) {
        return registry.get("contest.outbox.backlog")
                .tags("outbox", "judge", "status", status).gauge().value();
    }

    @SuppressWarnings("unchecked")
    private void stubBacklog(List<?> rows) {
        when(jdbcTemplate.query(contains("contest_judge_outbox"), any(RowMapper.class), anyInt()))
                .thenReturn((List<Object>) rows);
    }

    private static ResultSet row(String status, long count) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("status")).thenReturn(status);
        when(resultSet.getLong("row_count")).thenReturn(count);
        return resultSet;
    }
}
