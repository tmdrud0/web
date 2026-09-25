package my.oj.web.contest.submission.judge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ContestJudgeExecutionMetrics implements MeterBinder {

    private final String mode;
    private final String strategy;
    private volatile Meters meters;

    public ContestJudgeExecutionMetrics(
            @Value("${contest.submission.judge.dispatch-mode:rabbit}") String mode) {
        this.mode = mode;
        this.strategy = "mysql".equals(mode) ? "direct" : "rabbit";
        this.meters = Meters.of(new CompositeMeterRegistry(), mode, strategy);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        meters = Meters.of(registry, mode, strategy);
    }

    public void recordJudgement(long elapsedNanos) {
        meters.invocations().increment();
        meters.duration().record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * The whole of one processor call on a worker thread: the stored-result lookup, the judgement and
     * the wait for the result to be persisted and published. Its excess over contest.judge.duration is
     * the post-judge work a worker is held for (the MySQL dispatcher's outbox completion comes after).
     */
    public void recordProcessing(long elapsedNanos) {
        meters.processing().record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordStoredResultRepublish() {
        meters.storedResultRepublish().increment();
    }

    private record Meters(Counter invocations, Timer duration, Timer processing, Counter storedResultRepublish) {
        private static Meters of(MeterRegistry registry, String mode, String strategy) {
            return new Meters(
                    Counter.builder("contest.judge.invocations")
                            .tags("mode", mode, "strategy", strategy).register(registry),
                    Timer.builder("contest.judge.duration")
                            .tags("mode", mode, "strategy", strategy).register(registry),
                    Timer.builder("contest.judge.processing")
                            .tags("mode", mode, "strategy", strategy).register(registry),
                    Counter.builder("contest.judge.stored_result.republish")
                            .tags("mode", mode, "strategy", strategy).register(registry)
            );
        }
    }
}
