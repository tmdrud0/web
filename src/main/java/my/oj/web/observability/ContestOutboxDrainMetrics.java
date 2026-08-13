package my.oj.web.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;

/** Drain and retry counters for the remaining {@code contest_judge_outbox}. */
@Component
public class ContestOutboxDrainMetrics implements MeterBinder {

    public static final String JUDGE_OUTBOX = "judge";

    private volatile Counters counters = Counters.of(new CompositeMeterRegistry());

    @Override
    public void bindTo(MeterRegistry registry) {
        this.counters = Counters.of(registry);
    }

    public void recordJudgeRelay(int published, int retried) {
        counters.judgeDrained().increment(published);
        counters.judgeRetried().increment(retried);
    }

    private record Counters(Counter judgeDrained, Counter judgeRetried) {

        private static Counters of(MeterRegistry registry) {
            return new Counters(
                    Counter.builder("contest.outbox.drained")
                            .tag("outbox", JUDGE_OUTBOX)
                            .description("Judge outbox rows published to RabbitMQ and marked PUBLISHED")
                            .register(registry),
                    Counter.builder("contest.outbox.retries")
                            .tag("outbox", JUDGE_OUTBOX)
                            .description("Judge outbox publish attempts returned to PENDING")
                            .register(registry)
            );
        }
    }
}
