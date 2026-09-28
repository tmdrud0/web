package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.delivery.RabbitStreamDeliveryCondition;
import org.springframework.context.annotation.Conditional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
@Conditional(RabbitStreamDeliveryCondition.class)
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
    private volatile Counter rollbackObserved;
    private volatile Counter rollbackUnrecoverable;
    /**
     * One counter per outcome that leaves a range unanswered, so an interval spent retrying is
     * counted where it is decided rather than inferred from a repeated log line.
     *
     * <p>Only the retryable outcomes are registered, and {@link #recordRollbackRetry} refuses the
     * others rather than ignoring them: a covered or refused range is not a retry, and counting it as
     * one would report a hot loop where there is none.</p>
     */
    private final Map<ContestScoreboardRecoveryStrategy.Outcome, Counter> rollbackRetries =
            new EnumMap<>(ContestScoreboardRecoveryStrategy.Outcome.class);
    private volatile Counter failureRestarts;
    private volatile Counter unappliedRefusals;
    private volatile Counter tailProbeFailures;
    private volatile Counter checkpointRegressedRefusals;
    private final Map<String, Counter> rollbackDetections = new java.util.concurrent.ConcurrentHashMap<>();
    private MeterRegistry registry;

    /** A rollback seen by the supervisor's periodic comparison. */
    static final String DETECTED_BY_SUPERVISOR = "supervisor";
    /** A rollback seen by the checkpoint CAS of a batch being applied. */
    static final String DETECTED_BY_APPLY_CAS = "apply-cas";

    public ContestScoreboardStreamMetrics(MeterRegistry registry) {
        bindTo(registry);
    }

    private void bindTo(MeterRegistry registry) {
        this.registry = registry;
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
                .description("Stream batches that failed and were left unapplied")
                .register(registry);
        this.offsetGaps = Counter.builder("contest.scoreboard.stream.offset.gaps")
                .description("Requested offsets that were outside retained stream history")
                .register(registry);
        this.rollbackRestarts = Counter.builder("contest.scoreboard.stream.rollback.restarts")
                .description("Consumer restarts after the Redis offset rolled back")
                .register(registry);
        this.rollbackObserved = Counter.builder("contest.scoreboard.stream.rollback.observed")
                .description("Redis offset rollbacks a mode rebuilt from its own history basis instead"
                        + " of by rewinding the stream")
                .register(registry);
        this.rollbackUnrecoverable = Counter.builder("contest.scoreboard.stream.rollback.unrecoverable")
                .description("Rollback ranges a mode's basis reported it cannot rebuild in its current"
                        + " configuration, which are left missing and not asked about again")
                .register(registry);
        for (ContestScoreboardRecoveryStrategy.Outcome outcome : ContestScoreboardRecoveryStrategy.Outcome.values()) {
            if (outcome.retryable()) {
                rollbackRetries.put(outcome, Counter.builder("contest.scoreboard.stream.rollback.retry")
                        .tag("outcome", outcome.label())
                        .description("Rollback ranges a supervisor pass asked a mode's basis about again,"
                                + " because the earlier attempt left the range unanswered")
                        .register(registry));
            }
        }
        this.failureRestarts = Counter.builder("contest.scoreboard.stream.failure.restarts")
                .description("Consumer restarts to re-read a failed stream batch the broker does not redeliver")
                .register(registry);
        this.unappliedRefusals = Counter.builder("contest.scoreboard.stream.unapplied.refusals")
                .description("Deliveries refused because they began above an offset a failed batch left"
                        + " unapplied, with no checkpoint to hand a recovery mode")
                .register(registry);
        this.tailProbeFailures = Counter.builder("contest.scoreboard.stream.tail.probe.failures")
                .description("AMQP 0.9.1 probes that failed to observe the latest stream offset")
                .register(registry);
        this.checkpointRegressedRefusals = Counter.builder("contest.scoreboard.stream.checkpoint.regressed")
                .description("Stream batches the scoreboard refused without writing because its checkpoint was"
                        + " below what this consumer had already observed (the apply-time checkpoint CAS)")
                .register(registry);
        for (String path : List.of(DETECTED_BY_SUPERVISOR, DETECTED_BY_APPLY_CAS)) {
            rollbackDetection(path);
        }
    }

    private Counter rollbackDetection(String path) {
        return rollbackDetections.computeIfAbsent(path, key -> Counter.builder("contest.scoreboard.stream.rollback.detected")
                .tag("path", key)
                .description("Redis scoreboard rollbacks this consumer acted on, by the path that detected them")
                .register(registry));
    }

    /**
     * Counts a rollback this consumer acted on, apart from the restart and rebuild counters that say what
     * the action was: {@code supervisor} for the periodic comparison, {@code apply-cas} for a batch the
     * checkpoint CAS refused.
     */
    void recordRollbackDetected(String path) {
        rollbackDetection(path).increment();
    }

    void recordCheckpointRegressedRefusal() {
        checkpointRegressedRefusals.increment();
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

    void recordRollbackObserved() {
        rollbackObserved.increment();
    }

    void recordRollbackUnrecoverable() {
        rollbackUnrecoverable.increment();
    }

    /**
     * Counts a pass that asked a mode's basis about a rollback the earlier attempt left unanswered.
     *
     * @throws IllegalArgumentException for an outcome that answers the range, which is not a retry -
     *         a silent no-op here would read as one for whichever caller passed the wrong value
     */
    void recordRollbackRetry(ContestScoreboardRecoveryStrategy.Outcome outcome) {
        Counter counter = rollbackRetries.get(outcome);
        if (counter == null) {
            throw new IllegalArgumentException("A rollback whose outcome is " + outcome
                    + " has been answered and is not retried; only " + retryableLabels() + " are");
        }
        counter.increment();
    }

    private static List<String> retryableLabels() {
        return Arrays.stream(ContestScoreboardRecoveryStrategy.Outcome.values())
                .filter(ContestScoreboardRecoveryStrategy.Outcome::retryable)
                .map(ContestScoreboardRecoveryStrategy.Outcome::label)
                .toList();
    }

    void recordFailureRestart() {
        failureRestarts.increment();
    }

    void recordUnappliedRefusal() {
        unappliedRefusals.increment();
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
