package my.oj.web.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts rows leaving each outbox. Paired with {@link ContestOutboxBacklogMetrics}, this is what
 * makes the drain-time estimate of the pipeline history section 9.2 computable:
 *
 * <pre>{@code contest_outbox_backlog_rows / rate(contest_outbox_drained_total[5m])}</pre>
 *
 * <p>A backlog count on its own says how much is waiting but not whether it is going anywhere.
 * The two outboxes are the only places in the section 7 table where load accumulates on disk
 * without a ceiling, so the distinction between a deep backlog that is draining and one that is
 * not is the whole question.
 *
 * <p>Both drivers - the judge relay and the scoreboard worker - are conditional beans that only
 * exist on the role that owns them, so these counters are published once per pipeline stage
 * rather than once per JVM.
 */
@Component
public class ContestOutboxDrainMetrics implements MeterBinder {

    public static final String JUDGE_OUTBOX = "judge";
    public static final String SCOREBOARD_OUTBOX = "scoreboard";

    /**
     * A composite registry with no children discards every recording, so the counters are usable
     * before Spring binds this to the real registry and the record methods need no null check.
     */
    private volatile Counters counters = Counters.of(new CompositeMeterRegistry());

    @Override
    public void bindTo(MeterRegistry registry) {
        this.counters = Counters.of(registry);
    }

    /** @param published rows the relay moved to PUBLISHED, {@code retried} rows it put back as PENDING */
    public void recordJudgeRelay(int published, int retried) {
        counters.judgeDrained().increment(published);
        counters.judgeRetried().increment(retried);
    }

    /** @param completed rows the worker moved to COMPLETED, {@code retried} rows it moved to FAILED */
    public void recordScoreboardBatch(int completed, int retried) {
        counters.scoreboardDrained().increment(completed);
        counters.scoreboardRetried().increment(retried);
    }

    private record Counters(Counter judgeDrained,
                            Counter judgeRetried,
                            Counter scoreboardDrained,
                            Counter scoreboardRetried) {

        private static Counters of(MeterRegistry registry) {
            return new Counters(
                    drained(registry, JUDGE_OUTBOX, "published to RabbitMQ and marked PUBLISHED"),
                    retried(registry, JUDGE_OUTBOX, "publish failed and were returned to PENDING"),
                    drained(registry, SCOREBOARD_OUTBOX, "applied to the Redis scoreboard and marked COMPLETED"),
                    retried(registry, SCOREBOARD_OUTBOX, "apply failed and were marked FAILED for a backoff retry")
            );
        }

        private static Counter drained(MeterRegistry registry, String outbox, String detail) {
            return Counter.builder("contest.outbox.drained")
                    .tag("outbox", outbox)
                    .description("Rows that reached a terminal state: " + detail)
                    .register(registry);
        }

        private static Counter retried(MeterRegistry registry, String outbox, String detail) {
            return Counter.builder("contest.outbox.retries")
                    .tag("outbox", outbox)
                    .description("Rows whose attempt " + detail + ". These stay in the backlog, so "
                            + "a rising retry rate against a flat drain rate is a stuck pipeline "
                            + "rather than a slow one")
                    .register(registry);
        }
    }
}
