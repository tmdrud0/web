package my.oj.web.contest.scoreboard.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisContestScoreboardApplyMetricsTests {

    @Test
    void exportsPipelineHistogramBucketsForCrossInstancePercentiles() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RedisContestScoreboardApplyMetrics metrics = new RedisContestScoreboardApplyMetrics(registry);

        metrics.recordPipeline(Duration.ofMillis(7));

        String scrape = registry.scrape();
        assertThat(scrape).contains("contest_scoreboard_redis_pipeline_seconds_bucket{le=\"0.01\"} 1");
        assertThat(scrape).contains("contest_scoreboard_redis_pipeline_seconds_count 1");
    }

    @Test
    void classifiesStableLuaErrorFamilies() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RedisContestScoreboardApplyMetrics metrics = new RedisContestScoreboardApplyMetrics(registry);

        metrics.recordLuaError(new RedisCommandExecutionException(
                "ERR Unexpected Redis key type for contest:scoreboard:1:ranking"));
        metrics.recordLuaError(new RedisCommandExecutionException(
                "ERR Invalid integer value for c:penalty"));
        metrics.recordLuaError(new RedisCommandExecutionException(
                "ERR Incomplete scoreboard accepted attempt state"));

        String scrape = registry.scrape();
        assertThat(scrape)
                .contains("contest_scoreboard_redis_lua_errors_total{kind=\"unexpected_key_type\"} 1")
                .contains("contest_scoreboard_redis_lua_errors_total{kind=\"invalid_integer_field\"} 1")
                .contains("contest_scoreboard_redis_lua_errors_total{kind=\"incomplete_accepted_state\"} 1");
    }
}
