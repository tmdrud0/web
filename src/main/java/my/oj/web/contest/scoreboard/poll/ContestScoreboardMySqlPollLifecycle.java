package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the {@code mysql-poll} poll loop on its own thread.
 *
 * <p>Every tick catches its own failure: a fixed-delay task that throws is cancelled, which would stop the
 * delivery silently.</p>
 */
@Slf4j
public class ContestScoreboardMySqlPollLifecycle implements SmartLifecycle {

    private final ContestScoreboardMySqlPoller poller;
    private final ContestScoreboardPollOwnership ownership;
    private final ContestScoreboardMySqlPollMetrics metrics;
    private final ContestScoreboardMySqlPollProperties properties;
    private volatile ScheduledExecutorService executor;

    public ContestScoreboardMySqlPollLifecycle(ContestScoreboardMySqlPoller poller,
                                               ContestScoreboardPollOwnership ownership,
                                               ContestScoreboardMySqlPollMetrics metrics,
                                               ContestScoreboardMySqlPollProperties properties) {
        this.poller = poller;
        this.ownership = ownership;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        AtomicInteger threads = new AtomicInteger();
        ScheduledExecutorService started = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "scoreboard-mysql-poll-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        schedule(started, this::pollTick, Duration.ZERO, properties.pollInterval());
        executor = started;
        log.info("Contest scoreboard delivery: mysql-poll (batch-size={} poll-interval={} rollback-check-interval={}"
                        + " recovery-chunk-size={} recovery-max-iterations={})",
                properties.batchSize(), properties.pollInterval(), properties.rollbackCheckInterval(),
                properties.recoveryChunkSize(), properties.recoveryMaxIterations());
    }

    private static void schedule(ScheduledExecutorService executor, Runnable task, Duration initial, Duration delay) {
        executor.scheduleWithFixedDelay(task, initial.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    void pollTick() {
        try {
            if (!ownership.holds()) {
                return;
            }
            poller.pollOnce();
        } catch (RuntimeException failure) {
            metrics.recordFailure("poll");
            log.error("Scoreboard MySQL poll failed; the next tick retries", failure);
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService running = executor;
        executor = null;
        if (running == null) {
            return;
        }
        running.shutdown();
        try {
            if (!running.awaitTermination(10, TimeUnit.SECONDS)) {
                running.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.shutdownNow();
        }
        ownership.release();
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
