package my.oj.web.contest.submission.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

@Component
@ConditionalOnExpression(
        "'${contest.submission.judge.dispatch-mode:rabbit}' == 'mysql' && "
                + "'${contest.submission.judge.rabbit.listener.enabled:false}' == 'true'")
class MysqlContestJudgeMetrics implements MeterBinder {

    private static final String MODE = "mysql";
    private static final String STRATEGY = "direct";

    private volatile ExecutorState executor = ExecutorState.UNBOUND;
    private volatile Meters meters = Meters.of(new CompositeMeterRegistry());

    void bindExecutor(IntSupplier running, IntSupplier queued, IntSupplier reserved) {
        executor = new ExecutorState(running, queued, reserved);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("contest.judge.executor.running", this, self -> self.executor.running().getAsInt())
                .tags("mode", MODE, "strategy", STRATEGY).register(registry);
        Gauge.builder("contest.judge.executor.queued", this, self -> self.executor.queued().getAsInt())
                .tags("mode", MODE, "strategy", STRATEGY).register(registry);
        Gauge.builder("contest.judge.executor.reserved", this, self -> self.executor.reserved().getAsInt())
                .tags("mode", MODE, "strategy", STRATEGY).register(registry);
        meters = Meters.of(registry);
    }

    void recordClaim(long elapsedNanos, int rows, int staleRows) {
        meters.claimLatency().record(elapsedNanos, TimeUnit.NANOSECONDS);
        meters.claimCalls().increment();
        meters.claimRows().increment(rows);
        meters.claimBatch().record(rows);
        meters.staleReclaims().increment(staleRows);
    }

    void recordCompletion(String outcome, int count) {
        if (count <= 0) {
            return;
        }
        switch (outcome) {
            case "success" -> meters.completionSuccess().increment(count);
            case "failure" -> meters.completionFailure().increment(count);
            case "stale" -> meters.completionStale().increment(count);
            default -> throw new IllegalArgumentException("Unknown completion outcome: " + outcome);
        }
    }

    void recordRejection() {
        meters.rejections().increment();
    }

    private record ExecutorState(IntSupplier running, IntSupplier queued, IntSupplier reserved) {
        private static final ExecutorState UNBOUND = new ExecutorState(() -> 0, () -> 0, () -> 0);
    }

    private record Meters(Timer claimLatency,
                          Counter claimCalls,
                          Counter claimRows,
                          DistributionSummary claimBatch,
                          Counter staleReclaims,
                          Counter completionSuccess,
                          Counter completionFailure,
                          Counter completionStale,
                          Counter rejections) {
        private static Meters of(MeterRegistry registry) {
            return new Meters(
                    timer(registry, "contest.judge.claim.latency"),
                    counter(registry, "contest.judge.claim.calls", null),
                    counter(registry, "contest.judge.claim.rows", null),
                    DistributionSummary.builder("contest.judge.claim.batch")
                            .tags("mode", MODE, "strategy", STRATEGY).register(registry),
                    counter(registry, "contest.judge.claim.stale", null),
                    counter(registry, "contest.judge.completion", "success"),
                    counter(registry, "contest.judge.completion", "failure"),
                    counter(registry, "contest.judge.completion", "stale"),
                    counter(registry, "contest.judge.executor.rejections", null)
            );
        }

        private static Timer timer(MeterRegistry registry, String name) {
            return Timer.builder(name).tags("mode", MODE, "strategy", STRATEGY).register(registry);
        }

        private static Counter counter(MeterRegistry registry, String name, String outcome) {
            Counter.Builder builder = Counter.builder(name).tags("mode", MODE, "strategy", STRATEGY);
            if (outcome != null) {
                builder.tag("outcome", outcome);
            }
            return builder.register(registry);
        }
    }
}
