package my.oj.web.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import my.oj.web.contest.submission.queue.ContestSubmissionBulkMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A Micrometer meter id and the series name it exports are not the same string: base units and
 * counter suffixes are appended on the way out. A dashboard panel or an alert rule that names one
 * of these wrongly does not fail - the panel is empty and the alert never fires, both of which
 * look exactly like a healthy system.
 *
 * <p>So rather than listing the names by hand, this reads what the dashboard and the rules
 * actually query and checks each one against a real scrape. Drift in either direction fails here.
 *
 * <p>Only the {@code contest_*} families are checked. Everything else on the dashboard comes from
 * Micrometer, cAdvisor or an exporter, and is not this repository's to keep in step.
 */
class PipelineMetricNamesTests {

    private static final Path DASHBOARD = Path.of("observability/grafana/dashboards/oj-bottleneck.json");
    private static final Path RULES = Path.of("observability/prometheus/rules/oj-pipeline.yml");

    /**
     * The leading boundary excludes a colon so that recording rule names - {@code oj:contest_...}
     * - are not mistaken for exported series. They are defined by the rules file, not by the JVM.
     */
    private static final Pattern SERIES = Pattern.compile("(?<![:\\w])contest_[a-z0-9_]+");

    @Test
    void dashboardQueriesOnlySeriesTheApplicationExports() throws IOException {
        assertExported(seriesIn(readDashboardExpressions()), DASHBOARD);
    }

    @Test
    void alertRulesQueryOnlySeriesTheApplicationExports() throws IOException {
        assertExported(seriesIn(Files.readString(RULES)), RULES);
    }

    private void assertExported(Set<String> series, Path source) {
        assertThat(series)
                .as("no contest_* series found in %s - the extraction stopped matching", source)
                .isNotEmpty();
        String scrape = scrape();
        assertThat(series).allSatisfy(name -> assertThat(scrape)
                .as("%s queries %s, which nothing exports", source, name)
                .contains(name));
    }

    /** Every meter this repository registers, materialised by one recording each. */
    private static String scrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        ContestSubmissionBulkMetrics bulk = new ContestSubmissionBulkMetrics();
        bulk.bindTo(registry);
        bulk.bindSubmissionQueue(() -> 1, () -> 1, () -> 1, 4, 800);
        bulk.bindCompletionExecutor(() -> 1, () -> 1, 64, 4);
        bulk.recordInFlight(1);
        bulk.recordSuccess(100, 12L, 100, 0, 4);
        bulk.recordFailure(1, 1L, 0, 0, 4);
        bulk.recordRejectedSubmission();
        bulk.recordCompletion(100, 1L, 1L, 0, 4, true);
        bulk.recordCompletionCallerRuns();

        ContestOutboxDrainMetrics drain = new ContestOutboxDrainMetrics();
        drain.bindTo(registry);
        drain.recordJudgeRelay(1, 1);
        drain.recordScoreboardBatch(1, 1);

        new ContestOutboxBacklogMetrics(mock(JdbcTemplate.class), new ContestOutboxMetricsProperties(1_000))
                .bindTo(registry);

        return registry.scrape();
    }

    private static Set<String> seriesIn(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SERIES.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    private static String readDashboardExpressions() throws IOException {
        JsonNode dashboard = new ObjectMapper().readTree(Files.readString(DASHBOARD));
        StringBuilder expressions = new StringBuilder();
        dashboard.findValues("expr").forEach(expr -> expressions.append(expr.asText()).append('\n'));
        return expressions.toString();
    }
}
