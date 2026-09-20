package my.oj.web.submission.judge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Low-cardinality accounting for the deterministic load-test latency classes.
 *
 * <p>The existing judge metrics intentionally aggregate every invocation. This binder keeps the
 * same invocation boundary but splits it into only {@code fast} and {@code slow}; submission ids
 * are never used as tags.</p>
 */
@Component
public class ContestJudgeLatencyClassMetrics implements MeterBinder {

    static final String FAST = "fast";
    static final String SLOW = "slow";

    private final String mode;
    private final String strategy;
    private volatile Map<String, Meters> meters;

    public ContestJudgeLatencyClassMetrics(
            @Value("${contest.submission.judge.dispatch-mode:rabbit}") String mode) {
        this.mode = mode;
        this.strategy = "mysql".equals(mode) ? "direct" : "rabbit";
        this.meters = createMeters(new CompositeMeterRegistry());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        meters = createMeters(registry);
    }

    public void record(String latencyClass, long elapsedNanos) {
        Meters selected = meters.get(latencyClass);
        if (selected == null) {
            throw new IllegalArgumentException("Unknown judge latency class: " + latencyClass);
        }
        selected.invocations().increment();
        selected.duration().record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    private Map<String, Meters> createMeters(MeterRegistry registry) {
        return Map.of(
                FAST, Meters.of(registry, mode, strategy, FAST),
                SLOW, Meters.of(registry, mode, strategy, SLOW)
        );
    }

    private record Meters(Counter invocations, Timer duration) {
        private static Meters of(MeterRegistry registry, String mode, String strategy,
                                 String latencyClass) {
            return new Meters(
                    Counter.builder("contest.judge.latency.class.invocations")
                            .tags("mode", mode, "strategy", strategy, "latency_class", latencyClass)
                            .register(registry),
                    Timer.builder("contest.judge.latency.class.duration")
                            .tags("mode", mode, "strategy", strategy, "latency_class", latencyClass)
                            .register(registry)
            );
        }
    }
}
