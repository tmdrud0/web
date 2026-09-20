package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryCutover;
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
    private final ContestScoreboardRecoveryCutover cutover;
    /**
     * Whether Spring has started this lifecycle and has not stopped it again.
     *
     * <p>This is what {@link #isRunning()} answers, and it is deliberately not "the container is up".
     * The two come apart exactly while the consumer is held for a mode's own history recovery, and
     * Spring acts on the difference: {@code DefaultLifecycleProcessor.doStop} asks a bean to stop only
     * under {@code bean.isRunning()}. A lifecycle that reported itself stopped while it was still
     * waiting would be a bean the context closes without stopping - the {@link #stopping} flag below
     * would never be set, and the pass that finishes late would start a listener container into a
     * context that is already going down.</p>
     *
     * <p>What is being honoured is the lifecycle's own contract: start has been accepted and the
     * component is doing what it was configured to do, which for a held consumer is waiting for the
     * boundary its mode's recovery reports. Whether anything is being read from the broker is
     * {@link #consuming}.</p>
     */
    private volatile boolean started;
    /**
     * Whether the listener container is actually up and reading the stream.
     *
     * <p>Distinct from {@link #started} so that nothing mistakes a held consumer for a running one: the
     * supervisor pass answers an interval's question about a consumer that is reading, and a resubscribe
     * has nothing to resume. The guard below is this field, not {@code isRunning()}.</p>
     */
    private volatile boolean consuming;
    /**
     * Whether the context is going down, so a consumer released by the history-recovery boundary after
     * this point is not started into a closing context.
     *
     * <p>The boundary is reported by a startup pass, and the pass is allowed to run long - a full
     * replay of every contest does. A shutdown that arrives while it runs must not be followed by the
     * listener container starting underneath it.</p>
     */
    private volatile boolean stopping;
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
     * <p>Recorded only once the range has been answered: the rewind was performed, or the mode's basis
     * covered the range, or it refused the range outright. A pass that never ran because another held
     * the gate, and a pass that ran and failed, are not answers - nothing was learned about the range,
     * a later attempt may reach a different answer, and with no new stream delivery arriving nothing
     * else on this cadence would ask. Recording the pair before the outcome was known was what lost
     * those retries: a rollback that could not be rebuilt at that moment was taken for one that had
     * been, and with the checkpoint unable to move past it the pair never changed, so the history
     * stayed missing for as long as the JVM ran.</p>
     *
     * <p>Retrying is the deliberate cost. A rebuild that keeps failing is a full pass per supervisor
     * cycle, which is more work than the earlier behaviour did - and that behaviour is what left the
     * scoreboard short, so the work is the point. The retry is counted where it is decided, so an
     * interval spent retrying is visible rather than inferred from a log.</p>
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
            ContestScoreboardRecoveryStrategy strategy,
            ContestScoreboardRecoveryCutover cutover
    ) {
        this.container = container;
        this.applier = applier;
        this.completion = completion;
        this.position = position;
        this.metrics = metrics;
        this.properties = properties;
        this.strategy = strategy;
        this.cutover = cutover;
    }

    /**
     * Brings the consumer up, or holds it until the mode's own history recovery has run.
     *
     * <h2>Why the start is not unconditional</h2>
     *
     * <p>What this method asks the broker for is the stored checkpoint, and a consumer reading from
     * there re-reads every offset above it. On a JVM whose Redis was restored from a snapshot that is a
     * history recovery, and it is only {@code stream-offset} that means it as one - the other two
     * rebuild from MySQL and from the sequence, and a consumer that went first would put the restored
     * history back through the stream before either had a chance to. The container's own ordering is
     * the opposite of what they need: this lifecycle starts at the end of the context refresh, and the
     * passes that own their recovery are {@code ApplicationRunner}s, which run after it.</p>
     *
     * <p>So the wait is on {@link ContestScoreboardRecoveryCutover}, which the pass itself reports. The
     * consumer is not started at a different offset and nothing is skipped while it waits - the hold
     * changes when consumption begins, not where it begins.</p>
     *
     * <p>A held start counts as a started lifecycle ({@link #isRunning()}), because it is one: Spring
     * has been told the component is running, and the close that follows asks this instance to stop for
     * exactly that reason. What has not happened is the container coming up, which is
     * {@link #consuming()}.</p>
     */
    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        stopping = false;
        started = true;
        if (strategy.recoversHistoryBeforeConsuming()) {
            cutover.whenCovered(this::startAfterHistoryRecovery);
            if (!consuming) {
                // Only when it is still held: a restart inside a live context can reach the boundary
                // that has already been crossed, in which case the action ran above and started the
                // container, and logging "holding" over that would describe the state it just left.
                log.info("Holding the scoreboard stream consumer until the {} history recovery has run; the "
                                + "consumer will resume at the stored checkpoint, which the recovery does not move",
                        strategy.mode().propertyValue());
            }
            return;
        }
        startAtStoredOffset();
    }

    /**
     * The other half of a held start, run when the mode's history recovery reports the history covered.
     *
     * <p>Idempotent against the three ways it can be reached more than once: a lifecycle already
     * consuming (a shutdown and restart inside one context), a context that is going down, and the
     * cutover's own retry after this action failed. None may leave a listener container starting behind
     * them.</p>
     *
     * <p>A start that throws leaves {@link #consuming} false on purpose, so the cutover keeps the action
     * waiting and the next report of the boundary - the redis-seq periodic check is one - tries again
     * rather than leaving a consumer that never comes up.</p>
     */
    private synchronized void startAfterHistoryRecovery() {
        if (consuming || stopping) {
            return;
        }
        startAtStoredOffset();
    }

    /**
     * Stops the container if it is up, and records the close either way.
     *
     * <p>The flag is set before the container is touched, and it is set whether or not anything was
     * consuming: this is the only thing that keeps a release arriving during the close from starting a
     * container into it, and a consumer that is still held has no container to stop but is the one that
     * most needs the flag.</p>
     */
    @Override
    public synchronized void stop() {
        stopping = true;
        started = false;
        if (!consuming) {
            return;
        }
        container.stop();
        consuming = false;
    }

    @Override
    public void stop(Runnable callback) {
        synchronized (this) {
            stopping = true;
            started = false;
            if (!consuming) {
                callback.run();
                return;
            }
            consuming = false;
        }
        container.stop(callback);
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    /**
     * Whether the listener container is up, as opposed to started and waiting for the history boundary.
     *
     * <p>The question every pass that restarts a consumer has to ask: a held consumer has no position to
     * resume and no batch to re-read, so a supervisor that treated it as running would begin consuming
     * ahead of the recovery the hold exists for.</p>
     */
    boolean consuming() {
        return consuming;
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
     * instead is give their rebuild a trigger that does not depend on traffic arriving - and go on
     * giving it, cycle after cycle, until the range has been answered. A rebuild another pass held the
     * gate out of, or one that failed, is not an answer: it is retried here, because nothing else on
     * this deployment will ask again while the checkpoint cannot move past the range.</p>
     *
     * <p>A failed batch is the other way round and belongs to no mode in particular: the checkpoint is
     * right and the batch is not applied. Getting past it needs a resubscribe in every mode, because
     * nothing else brings that batch back. A stream queue accepts a requeueing rejection without
     * complaint and never hands the message to the running consumer again, which is measured in
     * {@code StreamQueueRequeueRabbitIntegrationTests}. This is a live-delivery repair rather than a
     * history recovery: what it resumes from is the consumer's own position, not a claim about which
     * results the standings are missing, so it does not put the stream back in the role of a recovery
     * basis for the modes that do not use it.</p>
     *
     * <p>Because the two are independent, this pass asks about them independently rather than treating
     * one as the other's alternative. A rollback whose rebuild has already been reported still leaves a
     * failed batch to re-read in the modes that answered it without touching the consumer, and the
     * checkpoint cannot move past that batch on its own - so the answer to the rollback is not a reason
     * to stop asking about the batch. In the rewinding mode the two coincide, because that mode's answer
     * <em>is</em> a restart at the checkpoint: it re-reads the failed batch on the way past, and this pass
     * counts the batch as handled rather than restarting a second time for it.</p>
     *
     * <p>What this pass asks about is a consumer that is reading. A held one is not asked about at all:
     * it has no position to resume from and no batch to re-read, and starting it here would begin
     * consumption ahead of the recovery the hold exists for - the very thing the boundary is. That is
     * why the guard is {@link #consuming()} rather than {@link #isRunning()}, which a held consumer
     * answers true to because Spring has to see it as started to stop it.</p>
     */
    void recoverConsumption() {
        if (!consuming) {
            return;
        }
        try {
            long storedOffset = applier.currentStreamOffset();
            long appliedOffset = position.highestAppliedOffset();
            long failures = position.failedBatches();
            boolean rolledBack = storedOffset < appliedOffset;
            boolean rollbackUnanswered = rolledBack
                    && !(storedOffset == answeredRollbackStoredOffset
                            && appliedOffset == answeredRollbackAppliedOffset);
            // Two things are asked about here and they are not alternatives: a rollback the mode has
            // not answered, and a failed batch. A mode that answers a rollback without touching the
            // consumer - the two whose basis is not the stream position - does not re-read the failed
            // batch on the way past, and the checkpoint cannot move past the batch on its own, so this
            // interval is the only thing that will ever ask about it. Treating the answered rollback as
            // a reason to stop asking would leave the standings short there for the life of the JVM.
            if (!rollbackUnanswered && failures <= handledFailures) {
                return;
            }
            synchronized (this) {
                if (!consuming) {
                    return;
                }
                boolean answeredNow = false;
                if (rollbackUnanswered) {
                    // Remembered only if this pass answered it. A rollback another pass is already
                    // rebuilding, or one whose rebuild failed, has to be asked about again - and with
                    // no new delivery arriving, this interval is the only thing that will ask.
                    if (!handleRollback(storedOffset, appliedOffset)) {
                        return;
                    }
                    answeredRollbackStoredOffset = storedOffset;
                    answeredRollbackAppliedOffset = appliedOffset;
                    answeredNow = true;
                }
                if (failures <= handledFailures) {
                    return;
                }
                if (answeredNow && strategy.rewindsOnCheckpointRegression()) {
                    // The rewind in this pass's answer was itself a restart at the checkpoint, which
                    // re-reads the failed batch on the way past. Restarting again would re-read the
                    // same range for the same batch.
                    handledFailures = failures;
                    return;
                }
                container.stop();
                metrics.recordFailureRestart();
                log.warn("Resubscribing the scoreboard stream consumer at {} to re-read a failed batch",
                        storedOffset);
                startAt(storedOffset, offsetValue(storedOffset));
                // Only once the container is back up. Recording the batch as handled before the
                // restart succeeded - while the container is stopped and the failure count is the only
                // thing that would ask about it again - is a retry thrown away: the next pass returns on
                // this comparison and nothing else in the JVM asks. A start that threw leaves the count
                // where it was, so the next cycle restarts again.
                handledFailures = failures;
            }
        } catch (RuntimeException failure) {
            log.warn("Could not inspect or restart the scoreboard stream consumer", failure);
        }
    }

    /**
     * Answers one observed rollback, and says whether the answer is complete.
     *
     * <p>The caller records the offsets as answered on true, so the return value is the whole of what
     * decides whether this range is ever looked at again. It is false for the two outcomes that are not
     * answers: a pass another pass held the gate out of, and one that ran and failed. Both leave the
     * range exactly as recoverable as they found it.</p>
     *
     * @return whether this pass answered the observed pair
     */
    private boolean handleRollback(long storedOffset, long appliedOffset) {
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
            ContestScoreboardRecoveryStrategy.Outcome outcome = strategy.rebuildHistory(range);
            if (outcome.covers()) {
                // The live path reads this so a range the supervisor already rebuilt is not rebuilt
                // again by the delivery that anchors past it. The watermark is what the rebuild was
                // marked at, and the live path asks about the offset below its delivery, so a
                // rebuild that did not reach as far as the current watermark is not taken for one
                // that did.
                position.markRebuiltThrough(appliedOffset);
                log.warn("Rebuilt the scoreboard history through offset {} with the {} basis; the consumer "
                                + "may now anchor the checkpoint past it",
                        appliedOffset, strategy.mode().propertyValue());
                return true;
            }
            if (outcome == ContestScoreboardRecoveryStrategy.Outcome.UNRECOVERABLE) {
                metrics.recordRollbackUnrecoverable();
                log.error("The {} basis cannot rebuild the history the rollback took away between offsets {} "
                                + "and {}; the scoreboard stays short there and the checkpoint stays put until "
                                + "someone replays from MySQL or changes the mode. The range is not asked "
                                + "about again until the observed offsets change, and not then either unless "
                                + "something can rebuild it - the refusal is remembered in this JVM only, so "
                                + "a restart asks again",
                        strategy.mode().propertyValue(), range.firstLostOffset(), range.lastLostOffset());
                return true;
            }
            metrics.recordRollbackRetry(outcome);
            log.warn("The {} basis did not rebuild the history the rollback took away between offsets {} and {} "
                            + "({}); the range is unanswered and is asked about again on the next supervisor "
                            + "cycle",
                    strategy.mode().propertyValue(),
                    range.firstLostOffset(),
                    range.lastLostOffset(),
                    outcome.label());
            return false;
        }
        container.stop();
        metrics.recordRollbackRestart();
        log.warn("Redis scoreboard offset rolled back from {} to {}; resubscribing from the stored offset",
                appliedOffset, storedOffset);
        startAt(storedOffset, offsetValue(storedOffset));
        // Rewinding is this mode's answer to a rollback and it has been carried out, so the observed
        // pair is answered even though the checkpoint has not moved yet: the resubscribe brings back
        // the results that move it. Without that, a checkpoint the broker no longer serves would mean
        // a stop and a start of the consumer on every pass.
        return true;
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
     *
     * <p>What the caller is told on return is that a consumer is up, which is asked of the container
     * rather than assumed. Spring AMQP sets the container's running flag before it starts its consumers
     * and does not clear it when that part fails, so {@code start()} can return having started nothing -
     * and a later attempt on that container returns immediately, because it already reports itself
     * running. Believing the return value there is how a consumer that is down gets recorded as one that
     * is up: the retry would be counted as served, the cutover would forget it, and the scoreboard would
     * stop being consumed with every log line saying otherwise.</p>
     */
    private void startAt(long storedOffset, Object requestedOffset) {
        completion.repairPending();
        position.consumerRestarted();
        metrics.initializeOffset(storedOffset);
        container.setConsumerArguments(Map.of("x-stream-offset", requestedOffset));
        container.start();
        if (container.getActiveConsumerCount() <= 0) {
            throw new IllegalStateException("The scoreboard stream listener container returned from start() "
                    + "with no active consumer: the container can report itself running while nothing is "
                    + "consuming, because Spring AMQP sets that flag before its consumers are started and "
                    + "leaves it set when that part fails. Nothing is consuming, so this start is reported "
                    + "as the failure it is - whoever asked for it keeps waiting and asks again");
        }
        consuming = true;
        log.info("Started scoreboard stream consumer at {}", requestedOffset);
    }

    private static Object offsetValue(long storedOffset) {
        return storedOffset < 0L ? "first" : storedOffset;
    }
}
