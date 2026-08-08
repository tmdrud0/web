package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
@Slf4j
class ContestScoreboardStreamLifecycle implements SmartLifecycle {

    private final SimpleMessageListenerContainer container;
    private final ContestScoreboardApplier applier;
    private final ContestScoreboardAppliedAtCompletion completion;
    private final ContestScoreboardStreamListener listener;
    private final ContestScoreboardStreamMetrics metrics;
    private volatile boolean running;

    ContestScoreboardStreamLifecycle(
            @Qualifier("contestScoreboardStreamListenerContainer") SimpleMessageListenerContainer container,
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamListener listener,
            ContestScoreboardStreamMetrics metrics
    ) {
        this.container = container;
        this.applier = applier;
        this.completion = completion;
        this.listener = listener;
        this.metrics = metrics;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        startAtStoredOffset();
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        container.stop();
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        synchronized (this) {
            if (!running) {
                callback.run();
                return;
            }
            running = false;
        }
        container.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Scheduled(fixedDelayString = "${contest.scoreboard.stream.consumer.offset-check-interval:1s}")
    void restartAfterRedisRollback() {
        if (!running) {
            return;
        }
        try {
            long storedOffset = applier.currentStreamOffset();
            if (storedOffset >= listener.highestAppliedOffset()) {
                return;
            }
            synchronized (this) {
                if (!running || storedOffset >= listener.highestAppliedOffset()) {
                    return;
                }
                log.warn(
                        "Redis scoreboard offset rolled back from {} to {}; resubscribing from the stored offset",
                        listener.highestAppliedOffset(),
                        storedOffset
                );
                container.stop();
                metrics.recordRollbackRestart();
                startAtStoredOffset();
            }
        } catch (RuntimeException failure) {
            log.warn("Could not inspect or restart the scoreboard stream consumer", failure);
        }
    }

    private void startAtStoredOffset() {
        completion.repairPending();
        long offset = applier.currentStreamOffset();
        listener.initializeOffset(offset);
        metrics.initializeOffset(offset);
        Object requestedOffset = offset < 0L ? "first" : offset + 1L;
        container.setConsumerArguments(Map.of("x-stream-offset", requestedOffset));
        container.start();
        log.info("Started scoreboard stream consumer at {}", requestedOffset);
    }
}
