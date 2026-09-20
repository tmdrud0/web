package my.oj.web.submission.judge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContestJudgeLatencyClassMetricsTests {

    @Test
    void recordsInvocationAndDurationByLowCardinalityLatencyClass() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContestJudgeLatencyClassMetrics metrics = new ContestJudgeLatencyClassMetrics("mysql");
        metrics.bindTo(registry);

        metrics.record("fast", TimeUnit.MILLISECONDS.toNanos(50));
        metrics.record("slow", TimeUnit.MILLISECONDS.toNanos(2_000));

        assertThat(registry.get("contest.judge.latency.class.invocations")
                .tag("latency_class", "fast").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("contest.judge.latency.class.invocations")
                .tag("latency_class", "slow").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("contest.judge.latency.class.duration")
                .tag("latency_class", "fast").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(50.0);
        assertThat(registry.get("contest.judge.latency.class.duration")
                .tag("latency_class", "slow").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(2_000.0);
    }

    @Test
    void refusesUnboundedOrUnknownClasses() {
        ContestJudgeLatencyClassMetrics metrics = new ContestJudgeLatencyClassMetrics("mysql");

        assertThatThrownBy(() -> metrics.record("submission-42", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("submission-42");
    }

    @Test
    void exposesThePrometheusSeriesConsumedByTheExperimentAnalyzer() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ContestJudgeLatencyClassMetrics metrics = new ContestJudgeLatencyClassMetrics("mysql");
        metrics.bindTo(registry);

        metrics.record("slow", TimeUnit.MILLISECONDS.toNanos(2_000));

        assertThat(registry.scrape())
                .contains("contest_judge_latency_class_invocations_total")
                .contains("contest_judge_latency_class_duration_seconds_count")
                .contains("contest_judge_latency_class_duration_seconds_sum")
                .contains("latency_class=\"slow\"");
    }
}
