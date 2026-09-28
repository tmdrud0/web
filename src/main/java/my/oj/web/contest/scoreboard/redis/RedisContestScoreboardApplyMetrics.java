package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Metrics for the Redis/Lua section shared by every scoreboard transport. */
public class RedisContestScoreboardApplyMetrics {

    private static final Duration[] PIPELINE_BUCKETS = {
            Duration.ofMillis(1),
            Duration.ofMillis(2),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            Duration.ofMillis(25),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2)
    };

    private final MeterRegistry registry;
    private final Timer batchLatency;
    private final DistributionSummary batchCalls;
    private final ConcurrentMap<String, Counter> luaErrors = new ConcurrentHashMap<>();

    public RedisContestScoreboardApplyMetrics(MeterRegistry registry) {
        this.registry = registry;
        // The meter keeps its old name on purpose: the harness scripts, the Grafana panels and the
        // earlier runs all read contest_scoreboard_redis_pipeline_seconds_*, and renaming it would cut
        // every comparison with them. What it measures is spelled out here instead - it was never a
        // Redis pipeline, and is now one batched EVAL per chunk.
        this.batchLatency = Timer.builder("contest.scoreboard.redis.pipeline")
                .description("Wall-clock duration of one Redis scoreboard applyAll batch: one batched Lua "
                        + "EVAL per chunk of contest.scoreboard.redis.apply-chunk-size events, sent in order "
                        + "and stopped at the first failure (not a Redis pipeline)")
                .serviceLevelObjectives(PIPELINE_BUCKETS)
                .register(registry);
        this.batchCalls = DistributionSummary.builder("contest.scoreboard.redis.apply.calls")
                .description("Redis round trips (batched Lua EVALs) spent on one applyAll batch")
                .baseUnit("calls")
                .register(registry);
    }

    /**
     * Records one applyAll batch.
     *
     * @param redisCalls how many scripts the batch sent to Redis - one per chunk reached
     */
    public void recordBatch(Duration duration, int redisCalls) {
        batchLatency.record(duration);
        batchCalls.record(redisCalls);
    }

    /** Classifies a failure the batched script reported as an event's result rather than raising. */
    public void recordLuaError(String failureMessage) {
        String kind = classify(failureMessage);
        luaErrors.computeIfAbsent(kind, this::registerLuaErrorCounter).increment();
    }

    public void recordLuaError(Throwable failure) {
        String kind = classify(failure);
        luaErrors.computeIfAbsent(kind, this::registerLuaErrorCounter).increment();
    }

    private Counter registerLuaErrorCounter(String kind) {
        return Counter.builder("contest.scoreboard.redis.lua.errors")
                .tag("kind", kind)
                .description("Redis scoreboard Lua failures classified by stable error family")
                .register(registry);
    }

    static String classify(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage());
            }
            current = current.getCause();
        }
        return classify(messages.toString());
    }

    static String classify(String failureMessage) {
        String message = failureMessage == null ? "" : failureMessage.toLowerCase(Locale.ROOT);
        if (message.contains("unexpected redis key type")) {
            return "unexpected_key_type";
        }
        if (message.contains("invalid integer value")) {
            return "invalid_integer_field";
        }
        if (message.contains("invalid submission id value")) {
            return "invalid_submission_id_field";
        }
        if (message.contains("invalid scoreboard numeric argument")) {
            return "invalid_numeric_argument";
        }
        if (message.contains("invalid scoreboard submission id argument")) {
            return "invalid_submission_id_argument";
        }
        if (message.contains("invalid negative scoreboard allocator sequence")) {
            return "negative_allocator_sequence";
        }
        if (message.contains("invalid scoreboard submission sequence")) {
            return "invalid_submission_sequence";
        }
        if (message.contains("processed scoreboard submission has no sequence mapping")) {
            return "missing_sequence_mapping";
        }
        if (message.contains("invalid scoreboard initialized flag")) {
            return "invalid_initialized_flag";
        }
        if (message.contains("incomplete scoreboard accepted attempt state")) {
            return "incomplete_accepted_state";
        }
        return "other";
    }
}
