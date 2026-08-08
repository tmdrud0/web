package my.oj.web.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqPerQueueMetricsConfigurationTests {

    private static final Path PROMETHEUS = Path.of("observability/prometheus/prometheus.yml");
    private static final Path DASHBOARD = Path.of("observability/grafana/dashboards/oj-bottleneck.json");
    private static final Path LOAD_SAMPLER = Path.of("gatling/sample-loadtest.ps1");
    private static final Path LOAD_HARNESS = Path.of("gatling/run-loadtest.ps1");
    private static final Pattern KEEP_REGEX = Pattern.compile("(?m)^\\s*regex:\\s*(rabbitmq_detailed_[^\\r\\n]+)$");

    private static final List<String> RETAINED_SERIES = List.of(
            "rabbitmq_detailed_queue_messages_ready",
            "rabbitmq_detailed_queue_messages_unacked",
            "rabbitmq_detailed_queue_consumers",
            "rabbitmq_detailed_queue_messages_delivered_ack_total",
            "rabbitmq_detailed_queue_messages_delivered_total",
            "rabbitmq_detailed_queue_exchange_messages_published_total"
    );

    @Test
    void detailedScrapeRequestsOnlyQueueOwnedFamiliesAndCapsAcceptedSamples() throws IOException {
        String config = Files.readString(PROMETHEUS);

        assertThat(config)
                .contains("job_name: rabbitmq-per-queue")
                .contains("metrics_path: /metrics/detailed")
                .contains("sample_limit: 100")
                .contains("vhost: [\"/\"]")
                .contains("queue_coarse_metrics")
                .contains("queue_consumer_count")
                .contains("queue_delivery_metrics")
                .contains("queue_exchange_metrics")
                .doesNotContain("channel_queue_metrics")
                .doesNotContain("channel_queue_exchange_metrics");

        Matcher matcher = KEEP_REGEX.matcher(config);
        assertThat(matcher.find()).as("the detailed target has no metric keep rule").isTrue();
        Pattern retained = Pattern.compile(matcher.group(1));
        assertThat(RETAINED_SERIES).allSatisfy(series -> assertThat(retained.matcher(series).matches())
                .as("%s is used by the queue dashboard but dropped at scrape time", series)
                .isTrue());
    }

    @Test
    void dashboardSeparatesLiveAndDeadQueuesUsingDetailedSeries() throws IOException {
        JsonNode dashboard = new ObjectMapper().readTree(Files.readString(DASHBOARD));
        StringBuilder expressions = new StringBuilder();
        dashboard.findValues("expr").forEach(expr -> expressions.append(expr.asText()).append('\n'));
        String queries = expressions.toString();

        assertThat(queries)
                .contains("queue=\"contest.judge.live\"")
                .contains("queue=\"contest.judge.dead\"")
                .contains("rabbitmq_detailed_queue_messages_ready")
                .contains("rabbitmq_detailed_queue_messages_unacked")
                .contains("rabbitmq_detailed_queue_consumers")
                .contains("rabbitmq_detailed_queue_exchange_messages_published_total")
                .contains("rabbitmq_detailed_queue_messages_delivered_ack_total")
                .contains("rabbitmq_detailed_queue_messages_delivered_total")
                .doesNotContain("rabbitmq_queue_messages_ready")
                .doesNotContain("rabbitmq_queue_messages_unacked")
                .doesNotContain("rabbitmq_global_messages_");
    }

    @Test
    void resultStreamIsSampledButExcludedFromWorkQueueDrain() throws IOException {
        String sampler = Files.readString(LOAD_SAMPLER);
        String harness = Files.readString(LOAD_HARNESS);

        assertThat(sampler)
                .contains("contest\\\\.judge\\\\.(live|dead|result\\\\.stream)")
                .contains("\"contest.judge.result.stream\" = @{");
        assertThat(harness)
                .contains("$parts[0] -in @(\"contest.judge.live\", \"contest.judge.dead\")")
                .doesNotContain("$parts[0] -in @(\"contest.judge.live\", \"contest.judge.dead\", \"contest.judge.result.stream\")");
    }
}
