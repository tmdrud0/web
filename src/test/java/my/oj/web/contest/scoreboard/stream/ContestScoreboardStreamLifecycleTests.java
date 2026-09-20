package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryCutover;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a restarted consumer asks the broker to begin, and what makes it restart at all.
 *
 * <p>The startup decision is not cosmetic: resuming at the stored checkpoint re-reads one event the
 * standings already hold, while starting at the beginning of retention re-reads all of it. Both are
 * correct - the script returns early for any offset at or below the checkpoint - but only one of them
 * is what an operator wants when the checkpoint itself is suspect, so the two must not be the same
 * code path with the same argument.</p>
 *
 * <p>What this pass does about a rollback belongs to the recovery mode and to nothing else. The
 * {@code stream-offset} mode rewinds, because the offset and the standings were rolled back together
 * and re-reading the stream is that mode's recovery. The other two do not: their history basis is
 * MySQL or the sequence, and stopping their consumer would both substitute a mechanism they do not own
 * and open the window in which a result published during the rebuild is missed. The tests below pin
 * both answers, because a supervisor that stopped the consumer in every mode is exactly the defect
 * that made the three modes incomparable.</p>
 *
 * <p>The supervisor pass has a second cause, and it is not a rollback: a failed batch leaves the
 * checkpoint exactly where it was, so the rollback guard sees nothing wrong while the batch is never
 * applied. Nothing at the broker brings it back either - a stream queue accepts a requeueing rejection
 * and does not redeliver - so the pass is the only thing that re-reads it, in every mode.</p>
 */
class ContestScoreboardStreamLifecycleTests {

    private SimpleMessageListenerContainer container;
    private ContestScoreboardApplier applier;
    private ContestScoreboardStreamPosition position;
    private ContestScoreboardRecoveryStrategy strategy;
    private ContestScoreboardRecoveryCutover cutover;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        container = mock(SimpleMessageListenerContainer.class);
        applier = mock(ContestScoreboardApplier.class);
        position = new ContestScoreboardStreamPosition();
        strategy = mock(ContestScoreboardRecoveryStrategy.class);
        cutover = new ContestScoreboardRecoveryCutover();
        registry = new SimpleMeterRegistry();
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.STREAM_OFFSET);
    }

    @Test
    void consumptionResumesAtTheStoredCheckpointInclusive() {
        when(applier.currentStreamOffset()).thenReturn(4L);

        lifecycle(StartupOffset.STORED).start();

        // The checkpoint itself, not its successor. Offsets are not consecutive integers, so asking
        // for 5 would assert that 5 exists - which is the contiguity this contract does not assume.
        assertThat(consumerArguments()).containsEntry("x-stream-offset", 4L);
    }

    @Test
    void theFirstRetainedOffsetIsUsedWhenTheCheckpointIsTheThingInDoubt() {
        when(applier.currentStreamOffset()).thenReturn(4L);

        lifecycle(StartupOffset.FIRST).start();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", "first");
        // Nothing about the scoreboard's own position is rewritten: the checkpoint is read from the
        // applier and never written here, and starting the consumer does not record the checkpoint as
        // something this process applied. See ContestScoreboardStreamPosition.consumerRestarted.
        assertThat(position.highestAppliedOffset()).isEqualTo(-1L);
    }

    @Test
    void aScoreboardWithNoCheckpointStartsAtTheBeginningEitherWay() {
        when(applier.currentStreamOffset()).thenReturn(-1L);

        lifecycle(StartupOffset.STORED).start();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", "first");
    }

    /**
     * The failure case, and the one this pass exists for: the batch was not applied, so the checkpoint
     * never moved, so a rollback guard alone would return early - and the batch is never re-read.
     */
    @Test
    void aFailedBatchIsReReadFromTheCheckpointThatWasNeverMoved() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordFailedBatch();

        lifecycle.recoverConsumption();

        // One start for the service coming up, one for the resubscribe that re-reads the batch - and
        // the batch is re-read because the checkpoint is handed back inclusive.
        verify(container, times(2)).start();
        assertThat(consumerArguments()).containsEntry("x-stream-offset", 4L);
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isZero();
    }

    /**
     * The distinction between the two causes, and why they are not one branch. A failure resumes at
     * the checkpoint because re-reading from there is what recovers the batch; only on the way up does
     * {@code startup-offset=first} distrust it. Honouring {@code first} here would re-read all of
     * retention to recover one batch.
     */
    @Test
    void aFailedBatchResumesAtTheCheckpointEvenWhenStartupDistrustsIt() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.FIRST);
        lifecycle.start();
        assertThat(consumerArguments()).containsEntry("x-stream-offset", "first");
        position.recordFailedBatch();

        lifecycle.recoverConsumption();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 4L);
    }

    /**
     * The cold start a mode that does not own the stream is allowed to have: nothing is read until that
     * mode's own history recovery has run.
     *
     * <p>The defect this pins is an ordering one and it is invisible from the outside. {@code full-replay}
     * and {@code redis-seq} rebuild history from MySQL and from the sequence, while a consumer resuming
     * at the stored checkpoint re-reads the history from the stream - and this lifecycle starts at the
     * end of the context refresh, ahead of the {@code ApplicationRunner}s that run those modes' passes.
     * Without a boundary the stream would have repaired the restored scoreboard before the mode the
     * operator selected had done anything, leaving the mode to replay over results that were already
     * back.</p>
     */
    @Test
    void aModeThatRebuildsHistoryFromItsOwnBasisConsumesNothingUntilItsPassHasRun() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);

        lifecycle.start();

        verify(container, never()).start();
        assertThat(lifecycle.isRunning()).isFalse();

        cutover.markCovered("the mode's startup pass");

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 4L);
        assertThat(lifecycle.isRunning()).isTrue();
    }

    /**
     * The stream's own mode is not held, and that is not a detail of this test: its history recovery
     * <em>is</em> the consumer reading from the stored checkpoint, so holding it until a pass that does
     * not exist would leave the scoreboard consuming nothing for good.
     */
    @Test
    void theModeThatRecoversByReadingTheStreamIsNotHeld() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(false);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);

        lifecycle.start();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 4L);
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(cutover.isCovered()).isFalse();
    }

    /**
     * Where a held consumer resumes is where it would have resumed anyway, which is the whole of the
     * claim that the hold costs no result: the pass that releases it writes no stream offset - a rebuild
     * request carries none - so the checkpoint the consumer asks for is the one it was held at.
     */
    @Test
    void aHeldConsumerResumesAtTheCheckpointTheHoldBeganAt() {
        when(applier.currentStreamOffset()).thenReturn(7L, 7L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        verify(container, never()).setConsumerArguments(any());

        cutover.markCovered("the mode's startup pass");

        assertThat(consumerArguments())
                .as("the stored checkpoint itself, never its successor and never a broker tail")
                .containsEntry("x-stream-offset", 7L);
    }

    /**
     * A release that arrives while the context is going down must not start a listener container behind
     * it. The pass that releases it is allowed to run long - a full replay of every contest does - so the
     * two can meet.
     */
    @Test
    void aConsumerReleasedAfterShutdownIsNotStarted() {
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.stop();
        cutover.markCovered("the mode's startup pass");

        verify(container, never()).start();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    /**
     * The supervisor is the one thing that must not start a held consumer on a timer: it runs on an
     * interval and it restarts the consumer for reasons of its own, so a pass that ignored the boundary
     * would begin consuming within a second of the JVM coming up - ahead of the recovery the wait exists
     * for.
     */
    @Test
    void theSupervisorDoesNotStartAHeldConsumer() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordFailedBatch();

        lifecycle.recoverConsumption();

        verify(container, never()).start();
    }

    /**
     * A failure already answered by a resubscribe must not restart the consumer on every pass.
     */
    @Test
    void aFailureIsAnsweredOnce() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordFailedBatch();

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(container, times(2)).start();
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isEqualTo(1.0);
    }

    /**
     * {@code stream-offset}: a rollback is answered by rewinding the consumer, and that is the
     * recovery itself rather than a workaround - the offset and the standings were rolled back
     * together, so re-reading from the checkpoint puts back what the rollback took away.
     */
    @Test
    void aRollbackIsRewoundToTheCheckpointWhenTheModeOwnsTheStream() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        // What makes the rollback visible: this process had applied up to 4 when Redis came back
        // holding 2. Starting the consumer records nothing of the sort - a resume is not an apply.
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 2L);
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isZero();
        verify(strategy, never()).rebuildHistory(any());
    }

    /**
     * The other two modes: a rollback is answered by rebuilding from their own basis, and the consumer
     * is left running.
     *
     * <p>Stopping it would be a defect in either direction. It would put the stream back in the role of
     * a history basis for a mode that does not use it, and a consumer that is not running is a consumer
     * that drops whatever is published while the rebuild runs - which is the one thing their recovery
     * cannot repair from MySQL, because those results were never lost.</p>
     */
    @Test
    void aRollbackLeavesTheConsumerRunningWhenTheModeDoesNotOwnTheStream() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.COVERED);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();

        verify(container, times(1)).start();
        verify(container, never()).stop();
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isZero();
        assertThat(counter("contest.scoreboard.stream.rollback.observed")).isEqualTo(1.0);
    }

    /**
     * The rebuild is asked about the range the rollback took away, and its success is what the live
     * path reads so the delivery that anchors past the range does not rebuild it a second time.
     */
    @Test
    void aRollbackTheModeRebuiltIsRecordedForTheLivePath() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.COVERED);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();

        ArgumentCaptor<ContestScoreboardRecoveryStrategy.LostRange> range =
                ArgumentCaptor.forClass(ContestScoreboardRecoveryStrategy.LostRange.class);
        verify(strategy).rebuildHistory(range.capture());
        assertThat(range.getValue().checkpointOffset()).isEqualTo(2L);
        assertThat(range.getValue().firstLostOffset()).isEqualTo(3L);
        // Both ends: the rollback took away everything from the checkpoint up to what this process
        // applied, and a rebuild that stopped short of that top is not one that covered the range.
        assertThat(range.getValue().lastLostOffset()).isEqualTo(4L);
        assertThat(range.getValue().highestAppliedOffset()).isEqualTo(4L);
        assertThat(position.rebuiltThrough()).isEqualTo(4L);
    }

    /**
     * A restart does not write over how far this process applied.
     *
     * <p>That watermark is the only trace in memory of a Redis rollback, and the checkpoint a
     * resubscribe resumes from sits behind it by definition - so a restart that wrote the resume
     * position into it would erase the very value this pass reads to decide that a rollback happened,
     * and a later restore to a point above the resume position would go unnoticed and unasked about.</p>
     */
    @Test
    void aRestartLeavesWhatThisProcessAppliedWhereItWas() {
        when(applier.currentStreamOffset()).thenReturn(9L, 6L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(9L);

        lifecycle.recoverConsumption();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 6L);
        assertThat(position.highestAppliedOffset())
                .as("the resume position is not the applied watermark")
                .isEqualTo(9L);
    }

    /**
     * A rebuild that did not cover the range must not be recorded as done: the live path reads that
     * record to decide whether it may anchor past the range, and a false record there would move the
     * checkpoint over results nobody put back.
     *
     * <p>The refusal is also not an answer that can be filed away - see
     * {@link #aRollbackTheBasisCouldNotAnswerIsAskedAboutAgain()}, which is the same range a cycle
     * later.</p>
     */
    @Test
    void aRollbackTheModeCouldNotRebuildIsNotRecordedAsDone() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.RETRYABLE_FAILURE);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();

        assertThat(position.rebuiltThrough()).isEqualTo(-1L);
        assertThat(counter("contest.scoreboard.stream.rollback.observed")).isEqualTo(1.0);
    }

    /**
     * The defect this pins: a rollback the mode could not answer at that moment was recorded as
     * answered and never looked at again.
     *
     * <p>The first cycle's attempt is refused the gate - another pass is rebuilding - so nothing was
     * learned about the range. The second cycle asks again at the same observed offsets, which is the
     * point: nothing new arrived from the stream, the checkpoint cannot move past the range, and the
     * scheduled pass is the only thing left that could ask. Only the third cycle's success is the
     * answer, and only then is the pair remembered.</p>
     */
    @Test
    void aRollbackTheBasisCouldNotAnswerIsAskedAboutAgain() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any()))
                .thenReturn(Outcome.BUSY_RETRY_LATER, Outcome.COVERED, Outcome.COVERED);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        assertThat(retryCount("busy-retry-later"))
                .as("a pass another pass held the gate out of is not an answer")
                .isEqualTo(1.0);
        assertThat(position.rebuiltThrough())
                .as("a range that was never rebuilt must not be recorded as rebuilt")
                .isEqualTo(-1L);

        lifecycle.recoverConsumption();
        assertThat(position.rebuiltThrough())
                .as("only the attempt that covered the range is the answer")
                .isEqualTo(4L);

        // Answered now, so the interval stops asking - three calls of the basis for two cycles that
        // did not answer it and one that did.
        lifecycle.recoverConsumption();
        verify(strategy, times(2)).rebuildHistory(any());
    }

    /**
     * A failure is retried as well, and counted apart from a busy gate: one says another pass is
     * running, the other says this one could not finish, and an operator needs to tell them apart.
     */
    @Test
    void aFailedRebuildIsRetriedAndCountedApartFromABusyGate() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any()))
                .thenReturn(Outcome.RETRYABLE_FAILURE, Outcome.RETRYABLE_FAILURE, Outcome.RETRYABLE_FAILURE);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(strategy, times(2)).rebuildHistory(any());
        assertThat(retryCount("retryable-failure")).isEqualTo(2.0);
        assertThat(retryCount("busy-retry-later")).isZero();
    }

    /**
     * A range the mode refuses outright is an answer, so it is remembered - and that is what keeps a
     * range nobody can rebuild from becoming a rebuild per interval.
     *
     * <p>It is an ERROR state and stays one: the range is not rebuilt, the checkpoint cannot move past
     * it, and the metric says so. What the memory buys is that the state is reported once rather than
     * driven into the log every second, which is not the same as it being repaired.</p>
     */
    @Test
    void anUnrecoverableRollbackIsRememberedAndLeftAsAnError() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.UNRECOVERABLE);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(strategy, times(1)).rebuildHistory(any());
        assertThat(position.rebuiltThrough()).isEqualTo(-1L);
        assertThat(counter("contest.scoreboard.stream.rollback.unrecoverable")).isEqualTo(1.0);
        assertThat(retryCount("busy-retry-later")).isZero();
        assertThat(retryCount("retryable-failure"))
                .as("a refusal is not a retry")
                .isZero();
    }

    /**
     * A mode that does not rewind leaves the checkpoint behind for as long as it takes the live path to
     * anchor past it, so the same rollback is visible on every pass. Answering it once per observed
     * pair is what keeps the interval from turning into a rebuild per interval.
     */
    @Test
    void aRollbackIsAnsweredOncePerObservedPair() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.COVERED);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(strategy, times(1)).rebuildHistory(any());
        assertThat(counter("contest.scoreboard.stream.rollback.observed")).isEqualTo(1.0);
    }

    /**
     * A consumer that was restarted is a new position, so the rollback must be judged again.
     */
    @Test
    void aFurtherRollbackIsAnsweredEvenAfterAnEarlierOne() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 1L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.COVERED);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        position.recordAppliedOffset(2L);
        lifecycle.recoverConsumption();

        verify(strategy, times(2)).rebuildHistory(any());
    }

    /**
     * The two causes are independent, and answering one is not answering the other.
     *
     * <p>This is the state the failure leaves behind in a mode that does not rewind: the rebuild has
     * reported the rollback handled, so the pair is remembered - and a batch is still unapplied below
     * the checkpoint the consumer's own position cannot pass. Nothing at the broker brings that batch
     * back, so if the answered rollback were taken as a reason to stop asking, the standings would stay
     * short there for the life of the JVM. The resubscribe is what re-reads it, in this mode as in every
     * other.</p>
     */
    @Test
    void aRollbackTheModeAnsweredStillLeavesTheFailedBatchToReRead() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.COVERED);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);
        position.recordFailedBatch();

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        assertThat(counter("contest.scoreboard.stream.rollback.restarts"))
                .as("a mode that does not own the stream does not restart the consumer for a rollback")
                .isZero();
        assertThat(counter("contest.scoreboard.stream.failure.restarts"))
                .as("the batch is re-read by a resubscribe at the checkpoint")
                .isEqualTo(1.0);
        assertThat(consumerArguments()).containsEntry("x-stream-offset", 2L);
        verify(strategy, times(1)).rebuildHistory(any());
    }

    /**
     * The rewinding mode is the one case the two causes coincide in, and it must not be restarted twice
     * for one batch: that mode's answer to the rollback <em>is</em> a restart at the checkpoint, and the
     * failed batch lies at or above that checkpoint, so the rewind re-reads it on the way past.
     */
    @Test
    void aRewindThatAnsweredTheRollbackDoesNotRestartAgainForTheFailedBatch() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);
        position.recordFailedBatch();

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        // One start for the service coming up, one for the rewind that re-read the batch.
        verify(container, times(2)).start();
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isZero();
        assertThat(consumerArguments()).containsEntry("x-stream-offset", 2L);
    }

    /** A healthy consumer must be left alone: this pass runs on an interval, all day. */
    @Test
    void aConsumerInStepWithTheScoreboardIsLeftAlone() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.recoverConsumption();

        verify(container, times(1)).start();
        verify(strategy, never()).rebuildHistory(any());
    }

    private ContestScoreboardStreamLifecycle lifecycle(StartupOffset startupOffset) {
        return new ContestScoreboardStreamLifecycle(
                container,
                applier,
                mock(ContestScoreboardAppliedAtCompletion.class),
                position,
                new ContestScoreboardStreamMetrics(registry),
                properties(startupOffset),
                strategy,
                cutover
        );
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    /**
     * The retry counter for one outcome. A pass that did not answer the range is counted under the
     * outcome that stopped it, so "another pass is running" and "the attempt failed" are told apart
     * here as well as in the pass itself.
     */
    private double retryCount(String outcome) {
        return registry.get("contest.scoreboard.stream.rollback.retry")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    /** The last consumer argument set, which is where the consumer most recently asked to begin. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> consumerArguments() {
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(container, atLeastOnce()).setConsumerArguments(arguments.capture());
        return arguments.getValue();
    }

    private static ContestScoreboardRecoveryProperties properties(StartupOffset startupOffset) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.STREAM_OFFSET,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 500, 3,
                        Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        startupOffset
                )
        , new ContestScoreboardRecoveryProperties.RecoveryOwner(true));
    }
}
