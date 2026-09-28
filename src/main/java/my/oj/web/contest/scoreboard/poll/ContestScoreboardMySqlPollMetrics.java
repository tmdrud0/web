package my.oj.web.contest.scoreboard.poll;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Meters of the {@code mysql-poll} delivery. Registered only with the poller, so a Stream deployment
 * does not publish a family of counters that can never move.
 */
public class ContestScoreboardMySqlPollMetrics {

    private final Counter applied;
    private final Counter rollbacks;
    private final Counter fences;
    private final Counter recovered;
    private final Counter rangesCompleted;
    private final Counter unresolved;
    private final MeterRegistry registry;
    private final Timer resume;
    private final Timer recoveryDuration;
    private final AtomicLong pendingRanges = new AtomicLong();
    private final AtomicLong rollbackDetectedAtNanos = new AtomicLong(-1L);

    public ContestScoreboardMySqlPollMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.applied = Counter.builder("contest.scoreboard.mysql.poll.applied")
                .description("Judged results the MySQL poller applied to the scoreboard").register(registry);
        this.rollbacks = Counter.builder("contest.scoreboard.mysql.poll.rollbacks")
                .description("Redis rollbacks detected as allocator below the MySQL watermark").register(registry);
        this.fences = Counter.builder("contest.scoreboard.mysql.poll.fences")
                .description("Allocator fences raised to the MySQL watermark").register(registry);
        this.recovered = Counter.builder("contest.scoreboard.mysql.poll.recovery.applied")
                .description("Results re-applied from pending recovery ranges").register(registry);
        this.rangesCompleted = Counter.builder("contest.scoreboard.mysql.poll.recovery.completed")
                .description("Pending recovery ranges completed").register(registry);
        this.unresolved = Counter.builder("contest.scoreboard.mysql.poll.recovery.unresolved")
                .description("Recovery passes that spent max-iterations and left a range pending").register(registry);
        this.resume = Timer.builder("contest.scoreboard.mysql.poll.rollback.resume")
                .description("From rollback detection to the next poll batch applied").register(registry);
        this.recoveryDuration = Timer.builder("contest.scoreboard.mysql.poll.recovery.duration")
                .description("From a range being opened in this JVM to its completion").register(registry);
        Gauge.builder("contest.scoreboard.mysql.poll.recovery.pending", pendingRanges, AtomicLong::get)
                .description("Pending recovery ranges seen by the last recovery pass").register(registry);
    }

    /** A poll batch applied {@code count} results; the first one after a rollback closes the resume timer. */
    void recordApplied(int count) {
        if (count <= 0) {
            return;
        }
        applied.increment(count);
        long detectedAt = rollbackDetectedAtNanos.getAndSet(-1L);
        if (detectedAt >= 0) {
            resume.record(Duration.ofNanos(System.nanoTime() - detectedAt));
        }
    }

    void recordRollback() {
        rollbacks.increment();
        rollbackDetectedAtNanos.set(System.nanoTime());
    }

    void recordFence() {
        fences.increment();
    }

    void recordRecovered(int count) {
        recovered.increment(count);
    }

    void recordRangeCompleted(Duration elapsed) {
        rangesCompleted.increment();
        if (elapsed != null) {
            recoveryDuration.record(elapsed);
        }
    }

    void recordUnresolved() {
        unresolved.increment();
    }

    void recordPendingRanges(int count) {
        pendingRanges.set(count);
    }

    void recordFailure(String task) {
        Counter.builder("contest.scoreboard.mysql.poll.failures")
                .description("Poll, check and recovery ticks that failed and will be retried")
                .tag("task", task)
                .register(registry)
                .increment();
    }
}
