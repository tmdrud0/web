package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
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
    private final ContestScoreboardRecoveryProperties properties;
    private volatile boolean running;
    /** The failure count already answered by a resubscribe, so one failure is not retried forever. */
    private long handledFailures;

    ContestScoreboardStreamLifecycle(
            @Qualifier("contestScoreboardStreamListenerContainer") SimpleMessageListenerContainer container,
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamListener listener,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardRecoveryProperties properties
    ) {
        this.container = container;
        this.applier = applier;
        this.completion = completion;
        this.listener = listener;
        this.metrics = metrics;
        this.properties = properties;
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

    /**
     * The supervisor pass, on the interval the operator configured.
     *
     * <p>Two things put a running consumer out of step with the scoreboard, and both are answered by
     * resubscribing - for different reasons.</p>
     *
     * <p>A Redis rollback leaves the checkpoint behind what this process applied, so resubscribing at
     * the stored offset re-reads what the rollback took away.</p>
     *
     * <p>A failed batch is the other way round: the checkpoint is right and the batch is not applied.
     * It needs the resubscribe because nothing else will bring that batch back. A stream queue accepts
     * a requeueing rejection without complaint and never hands the message to the running consumer
     * again, which is measured in {@code StreamQueueRequeueRabbitIntegrationTests}. Without this pass
     * the consumer would sit past a batch it never applied, and every later batch would fail the
     * script's continuity check against the frozen checkpoint - a stall that never clears on its own.</p>
     */
    void recoverConsumption() {
        if (!running) {
            return;
        }
        try {
            long storedOffset = applier.currentStreamOffset();
            long appliedOffset = listener.highestAppliedOffset();
            long failures = listener.failedBatches();
            if (storedOffset >= appliedOffset && failures <= handledFailures) {
                return;
            }
            synchronized (this) {
                if (!running) {
                    return;
                }
                container.stop();
                handledFailures = failures;
                if (storedOffset < appliedOffset) {
                    log.warn(
                            "Redis scoreboard offset rolled back from {} to {}; resubscribing from the stored offset",
                            appliedOffset,
                            storedOffset
                    );
                    metrics.recordRollbackRestart();
                    startAtStoredOffset();
                    return;
                }
                // The checkpoint is trusted here - this process applied up to it - so the batch is
                // resumed at the next offset rather than by re-reading retention, whatever the startup
                // policy says about a checkpoint on the way up.
                log.warn("Resubscribing the scoreboard stream consumer at {} to re-read a failed batch",
                        storedOffset + 1L);
                metrics.recordFailureRestart();
                startAt(storedOffset, resumeAfter(storedOffset));
            }
        } catch (RuntimeException failure) {
            log.warn("Could not inspect or restart the scoreboard stream consumer", failure);
        }
    }

    private void startAtStoredOffset() {
        long offset = applier.currentStreamOffset();
        startAt(offset, requestedOffset(offset));
    }

    private void startAt(long storedOffset, Object requestedOffset) {
        completion.repairPending();
        listener.initializeOffset(storedOffset);
        metrics.initializeOffset(storedOffset);
        container.setConsumerArguments(Map.of("x-stream-offset", requestedOffset));
        container.start();
        log.info("Started scoreboard stream consumer at {}", requestedOffset);
    }

    /**
     * Where the consumer asks the broker to begin.
     *
     * <p>The default resumes just after the checkpoint stored with the scoreboard. Starting at the
     * beginning of retention instead is the operator's way out of a checkpoint that is known to be
     * wrong: every message at or below it is re-delivered and the script returns the stored offset
     * without touching the standings, so the re-read costs traffic and changes nothing.</p>
     */
    private Object requestedOffset(long storedOffset) {
        if (properties.streamOffset().startupOffset()
                == ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.FIRST) {
            return "first";
        }
        return resumeAfter(storedOffset);
    }

    private static Object resumeAfter(long storedOffset) {
        return storedOffset < 0L ? "first" : storedOffset + 1L;
    }
}
