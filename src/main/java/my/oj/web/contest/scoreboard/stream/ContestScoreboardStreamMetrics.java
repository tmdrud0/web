package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
public class ContestScoreboardStreamMetrics {

    private final AtomicLong appliedOffset = new AtomicLong(-1L);
    private final AtomicLong latestOffset = new AtomicLong(-1L);
    private final AtomicLong readyBaseAgeNanos = new AtomicLong();
    private final AtomicLong readyRecordedNanos = new AtomicLong();
    private final AtomicLong ready = new AtomicLong();
    private volatile Counter applied;
    private volatile Counter failures;
    private volatile Counter offsetGaps;
    private volatile Counter rollbackRestarts;
    private volatile Counter tailProbeFailures;

    public ContestScoreboardStreamMetrics(
            MeterRegistry registry,
            ContestScoreboardStreamConsumerProperties properties
    ) {
        bindTo(registry);
    }

    private void bindTo(MeterRegistry registry) {
        Gauge.builder("contest.scoreboard.oldest.ready", this, ContestScoreboardStreamMetrics::oldestReadySeconds)
                .baseUnit("seconds")
                .description("Age of the oldest stream event currently ready for scoreboard application")
                .register(registry);
        Gauge.builder("contest.scoreboard.applied.offset", appliedOffset, AtomicLong::get)
                .baseUnit("offset")
                .description("Highest RabbitMQ Stream offset atomically reflected in Redis")
                .register(registry);
        Gauge.builder("contest.scoreboard.pending", this, ContestScoreboardStreamMetrics::pendingEvents)
                .baseUnit("events")
                .description("Latest observed RabbitMQ Stream offset minus the offset atomically applied in Redis")
                .register(registry);
        this.applied = Counter.builder("contest.scoreboard.applied")
                .description("Judged results applied to the scoreboard from RabbitMQ Stream")
                .register(registry);
        this.failures = Counter.builder("contest.scoreboard.stream.failures")
                .description("Stream batches left unacknowledged for retry")
                .register(registry);
        this.offsetGaps = Counter.builder("contest.scoreboard.stream.offset.gaps")
                .description("Requested offsets that were outside retained stream history")
                .register(registry);
        this.rollbackRestarts = Counter.builder("contest.scoreboard.stream.rollback.restarts")
                .description("Consumer restarts after the Redis offset rolled back")
                .register(registry);
        this.tailProbeFailures = Counter.builder("contest.scoreboard.stream.tail.probe.failures")
                .description("AMQP 0.9.1 probes that failed to observe the latest stream offset")
                .register(registry);
    }

    void recordBatchStarted(LocalDateTime oldestJudgedAt) {
        long now = System.nanoTime();
        long age = oldestJudgedAt == null
                ? 0L
                : Math.max(0L, Duration.between(oldestJudgedAt, LocalDateTime.now()).toNanos());
        readyBaseAgeNanos.set(age);
        readyRecordedNanos.set(now);
        ready.set(1L);
    }

    void recordApplied(List<Long> completedOffsets, long offset) {
        long previousOffset = appliedOffset.getAndSet(offset);
        long completed = completedOffsets.stream()
                .filter(java.util.Objects::nonNull)
                .filter(completedOffset -> completedOffset > previousOffset && completedOffset <= offset)
                .distinct()
                .count();
        if (completed > 0L) {
            applied.increment(completed);
        }
        ready.set(0L);
    }

    void initializeOffset(long offset) {
        appliedOffset.set(offset);
    }

    void recordLatestOffset(long offset) {
        latestOffset.accumulateAndGet(offset, Math::max);
    }

    void recordFailure() {
        failures.increment();
    }

    void recordOffsetGap() {
        offsetGaps.increment();
    }

    void recordRollbackRestart() {
        rollbackRestarts.increment();
    }

    void recordTailProbeFailure() {
        tailProbeFailures.increment();
    }

    private double pendingEvents() {
        return Math.max(0L, latestOffset.get() - appliedOffset.get());
    }

    private double oldestReadySeconds() {
        if (ready.get() == 0L) {
            return 0.0;
        }
        long now = System.nanoTime();
        return (readyBaseAgeNanos.get() + Math.max(0L, now - readyRecordedNanos.get())) / 1_000_000_000.0;
    }
}
