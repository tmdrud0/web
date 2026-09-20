package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
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
    private final ContestScoreboardStreamPosition position;
    private final ContestScoreboardStreamMetrics metrics;
    private final ContestScoreboardRecoveryProperties properties;
    private final ContestScoreboardRecoveryStrategy strategy;
    private volatile boolean running;
    /**
     * The failure count already answered by a resubscribe, so one failure is not retried forever.
     *
     * <p>Volatile because the pass that judges it runs on the scheduler thread while the value is
     * written under the monitor: without it a stale read costs a redundant stop/start of a consumer
     * whose failure was already answered.</p>
     */
    private volatile long handledFailures;
    /**
     * The rollback already answered, as the pair of offsets it was seen at.
     *
     * <p>A pair rather than a count because a mode that does not rewind leaves the checkpoint behind
     * for as long as it takes the live path to anchor past it, so the same rollback is still visible
     * on every pass. Answering it once and comparing the pair is what keeps a scheduled interval from
     * turning into a rebuild per interval.</p>
     *
     * <p>Answered once even when the rebuild failed. Retrying a failed rebuild on this cadence would
     * be a full MySQL replay per interval; the live path asks again on its next delivery, and the
     * mode's own triggers keep their own cadence, so a failure is not left unretried - only not
     * retried here and now.</p>
     */
    private volatile long answeredRollbackStoredOffset = Long.MIN_VALUE;
    private volatile long answeredRollbackAppliedOffset = Long.MIN_VALUE;

    ContestScoreboardStreamLifecycle(
            @Qualifier("contestScoreboardStreamListenerContainer") SimpleMessageListenerContainer container,
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamPosition position,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardRecoveryProperties properties,
            ContestScoreboardRecoveryStrategy strategy
    ) {
        this.container = container;
        this.applier = applier;
        this.completion = completion;
        this.position = position;
        this.metrics = metrics;
        this.properties = properties;
        this.strategy = strategy;
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
     * <h2>Two things put a running consumer out of step with the scoreboard</h2>
     *
     * <p>A Redis rollback leaves the checkpoint behind what this process applied. What to do about it
     * is the recovery mode's decision and only its decision - see
     * {@link ContestScoreboardRecoveryStrategy#rewindsOnCheckpointRegression()}. {@code stream-offset}
     * answers it by restarting the consumer at the stored offset, which is not a workaround for that
     * mode but the recovery itself: the offset and the standings were written together and rolled back
     * together, so re-reading from it is exactly what puts back what the rollback took away.</p>
     *
     * <p>The other two answer false, and this pass then touches nothing on the consumer. Their history
     * basis is MySQL and the sequence, neither of which is the stream position, so restarting the
     * consumer would replace their own recovery with a mechanism they do not own - and would stop the
     * one thing that guarantees no result published during the rebuild is missed. What this pass does
     * instead is give their rebuild a trigger that does not depend on traffic arriving.</p>
     *
     * <p>A failed batch is the other way round and belongs to no mode in particular: the checkpoint is
     * right and the batch is not applied. Getting past it needs a resubscribe in every mode, because
     * nothing else brings that batch back. A stream queue accepts a requeueing rejection without
     * complaint and never hands the message to the running consumer again, which is measured in
     * {@code StreamQueueRequeueRabbitIntegrationTests}. This is a live-delivery repair rather than a
     * history recovery: what it resumes from is the consumer's own position, not a claim about which
     * results the standings are missing, so it does not put the stream back in the role of a recovery
     * basis for the modes that do not use it.</p>
     */
    void recoverConsumption() {
        if (!running) {
            return;
        }
        try {
            long storedOffset = applier.currentStreamOffset();
            long appliedOffset = position.highestAppliedOffset();
            long failures = position.failedBatches();
            boolean rolledBack = storedOffset < appliedOffset;
            if (rolledBack) {
                if (storedOffset == answeredRollbackStoredOffset
                        && appliedOffset == answeredRollbackAppliedOffset) {
                    return;
                }
            } else if (failures <= handledFailures) {
                return;
            }
            synchronized (this) {
                if (!running) {
                    return;
                }
                if (rolledBack) {
                    answeredRollbackStoredOffset = storedOffset;
                    answeredRollbackAppliedOffset = appliedOffset;
                    handleRollback(storedOffset, appliedOffset);
                    return;
                }
                container.stop();
                handledFailures = failures;
                metrics.recordFailureRestart();
                log.warn("Resubscribing the scoreboard stream consumer at {} to re-read a failed batch",
                        storedOffset);
                startAt(storedOffset, offsetValue(storedOffset));
            }
        } catch (RuntimeException failure) {
            log.warn("Could not inspect or restart the scoreboard stream consumer", failure);
        }
    }

    private void handleRollback(long storedOffset, long appliedOffset) {
        if (!strategy.rewindsOnCheckpointRegression()) {
            position.clearAnchorVerified();
            metrics.recordRollbackObserved();
            log.warn("Redis scoreboard offset rolled back from {} to {}; mode={} rebuilds history from its "
                            + "own basis and leaves the consumer where it is, so nothing published while it "
                            + "rebuilds is missed",
                    appliedOffset, storedOffset, strategy.mode().propertyValue());
            ContestScoreboardRecoveryStrategy.LostRange range = new ContestScoreboardRecoveryStrategy.LostRange(
                    storedOffset,
                    appliedOffset,
                    appliedOffset,
                    position.rebuiltThrough()
            );
            if (strategy.rebuildHistory(range)) {
                // The live path reads this so a range the supervisor already rebuilt is not rebuilt
                // again by the delivery that anchors past it. The watermark is what the rebuild was
                // marked at, and the live path asks about the offset below its delivery, so a
                // rebuild that did not reach as far as the current watermark is not taken for one
                // that did.
                position.markRebuiltThrough(appliedOffset);
                log.warn("Rebuilt the scoreboard history through offset {} with the {} basis; the consumer "
                                + "may now anchor the checkpoint past it",
                        appliedOffset, strategy.mode().propertyValue());
                return;
            }
            log.error("The {} basis could not rebuild the history the rollback took away between offsets {} "
                            + "and {}; the scoreboard stays short there and the checkpoint stays put until "
                            + "something can",
                    strategy.mode().propertyValue(), range.firstLostOffset(), range.lastLostOffset());
            return;
        }
        container.stop();
        metrics.recordRollbackRestart();
        log.warn("Redis scoreboard offset rolled back from {} to {}; resubscribing from the stored offset",
                appliedOffset, storedOffset);
        startAt(storedOffset, offsetValue(storedOffset));
    }

    private void startAtStoredOffset() {
        long offset = applier.currentStreamOffset();
        if (properties.streamOffset().startupOffset()
                == ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.FIRST) {
            startAt(offset, "first");
            return;
        }
        startAt(offset, offsetValue(offset));
    }

    /**
     * Starts the consumer at a fresh position, which is the question it will answer next.
     *
     * <p>What the resume does not do is overwrite how far this process applied. That watermark is the
     * only in-memory trace of a Redis rollback, and the checkpoint a resubscribe resumes from can sit
     * behind it - which is precisely the rollback the mode still has to be asked about.</p>
     *
     * <p>The position handed to the broker is the checkpoint itself rather than its successor. The
     * offset is the last one the scoreboard applied, so asking for it re-delivers one event the
     * standings already hold, which the script absorbs without touching them - and it is what makes
     * the consumer's position verifiable. Asking for the successor would instead assert that the
     * checkpoint's successor is the next offset that exists, which is exactly the contiguity this
     * contract does not assume.</p>
     *
     * <p>Handing over an unset checkpoint asks for the beginning of retention, because there is no
     * position to resume from. The first offset the broker then delivers becomes the checkpoint,
     * whatever number it happens to be - nothing here requires a stream to start at zero or one.</p>
     *
     * <p>Resuming at the beginning instead of at the checkpoint is the operator's way out of a
     * checkpoint known to be wrong. Every message at or below it is re-delivered and the script
     * returns the stored offset without touching the standings, so the re-read costs traffic and
     * changes nothing.</p>
     */
    private void startAt(long storedOffset, Object requestedOffset) {
        completion.repairPending();
        position.consumerRestarted();
        metrics.initializeOffset(storedOffset);
        container.setConsumerArguments(Map.of("x-stream-offset", requestedOffset));
        container.start();
        log.info("Started scoreboard stream consumer at {}", requestedOffset);
    }

    private static Object offsetValue(long storedOffset) {
        return storedOffset < 0L ? "first" : storedOffset;
    }
}
