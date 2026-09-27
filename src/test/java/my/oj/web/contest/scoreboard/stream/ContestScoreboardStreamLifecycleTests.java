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
import org.springframework.amqp.AmqpIllegalStateException;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
        // A container that has started its consumers. The lifecycle asks this of the container rather
        // than trusting start() to have done something, so a mock that answered 0 would make every start
        // here look like a failed one.
        //
        // What this mock cannot answer is what a start that failed does to the container: it has no
        // state to be left in. The tests for that build the lifecycle on StatefulListenerContainer
        // instead, and the sequential getActiveConsumerCount() stubs they replaced are the reason why -
        // see aStartOnAContainerThatStillReportsItselfRunningIsANoOp.
        lenient().when(container.getActiveConsumerCount()).thenReturn(1);
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
        assertThat(lifecycle.consuming())
                .as("nothing is reading the stream, so no pass may take this consumer for a running one")
                .isFalse();
        assertThat(lifecycle.isRunning())
                .as("started and waiting on the boundary - a lifecycle Spring has to be able to stop")
                .isTrue();

        cutover.markCovered("the mode's startup pass");

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 4L);
        assertThat(lifecycle.consuming()).isTrue();
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
     *
     * <p>What reaches this guard in a real deployment is the close, not a call to {@code stop()} by hand:
     * Spring asks a bean it considers running to stop, which is what the held lifecycle reports itself as
     * - see {@code ContestScoreboardStreamLifecycleContextTests}, which closes a real context for exactly
     * this reason.</p>
     */
    @Test
    void aConsumerReleasedAfterShutdownIsNotStarted() {
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.stop();
        cutover.markCovered("the mode's startup pass");

        verify(container, never()).start();
        assertThat(lifecycle.consuming()).isFalse();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    /**
     * Spring's own stop and start of one bean, which is the other way this lifecycle is restarted inside
     * one context. The hold is re-armed rather than skipped: a stop is not a way past the mode's history
     * recovery, and the consumer still starts exactly once when the boundary is finally reported.
     */
    @Test
    void aRestartAfterAStopIsHeldAgain() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.stop();
        lifecycle.start();

        verify(container, never()).start();

        cutover.markCovered("the mode's startup pass");

        verify(container, times(1)).start();
        assertThat(lifecycle.consuming()).isTrue();
    }

    /**
     * The supervisor is the one thing that must not start a held consumer on a timer: it runs on an
     * interval and it restarts the consumer for reasons of its own, so a pass that ignored the boundary
     * would begin consuming within a second of the JVM coming up - ahead of the recovery the wait exists
     * for. The lifecycle reports itself started while it waits, which is why the guard is the container
     * state and not {@code isRunning()}.
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
        assertThat(lifecycle.isRunning())
                .as("the pass declines to act, not because the lifecycle is stopped")
                .isTrue();
        assertThat(lifecycle.consuming()).isFalse();
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

    @Test
    void liveProgressIsRememberedWithoutBeingRecordedAsRebuilt() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.LIVE_PROGRESS);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(strategy, times(1)).rebuildHistory(any());
        assertThat(position.rebuiltThrough()).isEqualTo(-1L);
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
        return lifecycle(container, startupOffset);
    }

    /**
     * The same lifecycle on a container with state of its own, which is the only kind that can answer
     * what a failed start left behind.
     */
    private ContestScoreboardStreamLifecycle lifecycle(
            SimpleMessageListenerContainer listenerContainer, StartupOffset startupOffset) {
        return new ContestScoreboardStreamLifecycle(
                listenerContainer,
                applier,
                mock(ContestScoreboardAppliedAtCompletion.class),
                position,
                new ContestScoreboardStreamMetrics(registry),
                properties(startupOffset),
                strategy,
                cutover
        );
    }

    /**
     * The property this round turns on, on the container model itself: a start on a container that still
     * reports itself running is Spring AMQP's early return and nothing else.
     *
     * <p>It is asserted rather than assumed because every test below reads the container the way the
     * lifecycle has to. A model that let a second start through would make them all pass without anything
     * being repaired - which is the sequential stub's mistake, moved into a new place.</p>
     */
    @Test
    void aStartOnAContainerThatStillReportsItselfRunningIsANoOp() {
        StatefulListenerContainer container = new StatefulListenerContainer();
        container.willLeaveNoConsumer();
        container.start();

        // What a retry meets if nothing took the container back out of the half-started state.
        container.willStartAConsumer();
        container.start();

        assertThat(container.skippedStarts())
                .as("the consumer the second start was set up to bring never got the chance")
                .isEqualTo(1);
        assertThat(container.getActiveConsumerCount()).isZero();
        assertThat(container.reportsItselfRunning()).isTrue();
    }

    /**
     * A start that left no consumer behind is taken back out of the state that would swallow the retry,
     * and the retry then really starts one.
     *
     * <p>Spring AMQP raises the container's running flag before it starts its consumers and does not
     * lower it when that part fails, so a container whose start half-failed reports itself running with
     * nothing consuming - and {@code start()} returns at its first line for every attempt after that.
     * Reporting the failure without taking the container back out of that state is a retry that only
     * looks like one: the action stays waiting and is retried against a container that is permanently a
     * no-op.</p>
     *
     * <p>The stub this replaced could not see any of that. {@code getActiveConsumerCount()} answering
     * {@code 0} and then {@code 1} lets any second start look successful, while a real container refuses
     * one that was never taken out of the half-started state - so the count of skipped starts below is
     * what tells a retry that worked from one that was waved through.</p>
     */
    @Test
    void aStartThatLeftNoConsumerIsTakenBackOutAndTheRetryReallyStartsOne() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        StatefulListenerContainer container = new StatefulListenerContainer();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, StartupOffset.STORED);
        lifecycle.start();
        // The broker is not ready: the flag goes up - Spring AMQP raises it before its consumers - and
        // no consumer comes with it.
        container.willLeaveNoConsumer();

        assertThatThrownBy(() -> cutover.markCovered("the mode's startup pass"))
                .as("a release that started nothing is not swallowed")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active consumer");
        assertThat(lifecycle.consuming()).isFalse();
        assertThat(container.reportsItselfRunning())
                .as("left where the failed start put it, this is the state that makes every later "
                        + "start a no-op")
                .isFalse();
        assertThat(container.stops())
                .as("the container was taken back out before the failure was reported")
                .isEqualTo(1);

        // The broker comes back, and the report that was kept waiting is the retry.
        container.willStartAConsumer();
        cutover.markCovered("the redis-seq duplicate-check check");

        assertThat(lifecycle.consuming()).isTrue();
        assertThat(container.consumerArguments()).containsEntry("x-stream-offset", 4L);
        assertThat(container.skippedStarts())
                .as("no attempt was thrown away on a container that still reported itself running")
                .isZero();

        // Served, so it is not waiting any more: a third report does not start a second consumer.
        cutover.markCovered("the redis-seq lost-tail check");
        assertThat(container.attempts()).isEqualTo(2);
    }

    /**
     * The other shape of a failed start: it throws. The broker's own failure is what the caller hears,
     * and the container is still taken back out of the half-started state first - Spring AMQP raises the
     * flag before the part that fails here too, and its {@code start()} does not lower it on the way out.
     */
    @Test
    void aStartThatThrewIsStillRetriedAfterTheContainerIsTakenBackOut() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        StatefulListenerContainer container = new StatefulListenerContainer();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, StartupOffset.STORED);
        lifecycle.start();
        container.willFailToStart();

        assertThatThrownBy(() -> cutover.markCovered("the mode's startup pass"))
                .as("the failure is reported as the container raised it, not as something invented here")
                .isInstanceOf(AmqpIllegalStateException.class)
                .hasMessageContaining("Fatal exception on listener startup");
        assertThat(lifecycle.consuming()).isFalse();
        assertThat(container.reportsItselfRunning()).isFalse();

        container.willStartAConsumer();
        cutover.markCovered("the redis-seq duplicate-check check");

        assertThat(lifecycle.consuming()).isTrue();
        assertThat(container.skippedStarts()).isZero();
    }

    /**
     * When the stop that takes the container back out fails, the start's failure is still the one
     * reported - and the stop's is reported beside it. Neither is dropped: the caller has to hear about
     * the start, and an operator has to hear that the repair did not go cleanly.
     *
     * <p>The retry works anyway, and that is not luck: Spring AMQP lowers the container's running flag in
     * a {@code finally}, so the flag is down even when the stop throws.</p>
     */
    @Test
    void aStopThatCouldNotTakeTheContainerBackOutDoesNotReplaceTheStartFailure() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        StatefulListenerContainer container = new StatefulListenerContainer();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, StartupOffset.STORED);
        lifecycle.start();
        container.willLeaveNoConsumer();
        container.willFailToStop(new IllegalStateException("the container could not be stopped"));

        IllegalStateException failure = catchThrowableOfType(
                IllegalStateException.class,
                () -> cutover.markCovered("the mode's startup pass"));

        assertThat(failure)
                .as("the start's own failure is the one on top")
                .hasMessageContaining("no active consumer");
        assertThat(failure.getSuppressed())
                .as("and the stop's is reported with it, not instead of it")
                .hasSize(1);
        // The same object, not a re-wrapped one: the repair hands the stop's own failure to the caller
        // rather than reporting one it built. That it is the same *text* is this model's doing - a real
        // container puts a non-AMQP stop failure through convertRabbitAccessException, which would
        // prefix it - so what is asserted is that the failure is passed through, and the message is how
        // that is read here.
        assertThat(failure.getSuppressed()[0]).hasMessage("the container could not be stopped");
        assertThat(container.reportsItselfRunning())
                .as("Spring AMQP lowers the flag in a finally, so even that stop left it startable")
                .isFalse();

        container.willStartAConsumer();
        cutover.markCovered("the redis-seq duplicate-check check");

        assertThat(lifecycle.consuming()).isTrue();
    }

    /**
     * Nothing is being repaired on a start that worked, so the consumer that came up is left alone.
     *
     * <p>This one passes on the implementation before the repair as well - nothing was stopped there
     * either - so it is not evidence of the defect. It is here so that the repair cannot grow into an
     * unconditional stop without a test noticing, which is the way this fix could do harm.</p>
     */
    @Test
    void aConsumerThatCameUpIsNotStopped() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        StatefulListenerContainer container = new StatefulListenerContainer();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, StartupOffset.STORED);

        lifecycle.start();

        assertThat(lifecycle.consuming()).isTrue();
        assertThat(container.stops())
                .as("the repair is for a start that failed, and this one did not")
                .isZero();
    }

    /**
     * A resubscribe that could not bring the container back is asked about again.
     *
     * <p>The failed batch has no other way of coming back: the checkpoint cannot move past it, and
     * nothing at the broker redelivers it. Recording the batch as handled before the restart succeeded
     * - while the failure count is the only thing left to ask - is a retry thrown away for the life of
     * the JVM, which is the same loss the rollback side of this pass was fixed for.</p>
     */
    @Test
    void aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        StatefulListenerContainer container = new StatefulListenerContainer();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, StartupOffset.STORED);
        lifecycle.start();
        position.recordFailedBatch();
        // The resubscribe's own start half-fails: the flag goes up and no consumer comes with it. The
        // resubscribe stopped the container first, so the flag is up here only because this start
        // raised it again - and it is up with nothing behind it, which is what the next cycle would
        // meet as an early return.
        container.willLeaveNoConsumer();

        lifecycle.recoverConsumption();
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isEqualTo(1.0);
        assertThat(container.getActiveConsumerCount()).isZero();
        assertThat(container.reportsItselfRunning()).isFalse();

        container.willStartAConsumer();
        lifecycle.recoverConsumption();

        assertThat(counter("contest.scoreboard.stream.failure.restarts"))
                .as("the restarted consumer is what re-reads the batch, so a restart that failed is not "
                        + "a retry spent")
                .isEqualTo(2.0);
        assertThat(lifecycle.consuming()).isTrue();
        assertThat(container.consumerArguments()).containsEntry("x-stream-offset", 4L);
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

    /**
     * The listener container as Spring AMQP behaves, as state rather than as a script of stubbed returns.
     *
     * <h2>Why a state model instead of the sequential stub</h2>
     *
     * <p>{@code getActiveConsumerCount()} answering {@code 0} and then {@code 1} says nothing about the
     * container. A real one keeps the running flag its failed start raised and answers every later
     * {@code start()} with an early return, so a second start against it starts nothing however the
     * count is stubbed - and a test built on those stubs cannot tell a start that was retried from one
     * that was skipped. That difference is the whole of what the tests above are about, so the model
     * carries the state instead: the flag, the consumers behind it, and what a start does to both.</p>
     *
     * <p>{@code start()} therefore reads its own state rather than counting calls, and {@code stop()}
     * lowers the flag whether or not it has been asked to fail - which is what makes it the call that can
     * always take a container back out of a half-started state.</p>
     *
     * <h2>What it does not reproduce</h2>
     *
     * <p>The broker, the consumers themselves, and the real {@code stop(Runnable)} ordering, where a real
     * container hands the wait to a task executor and can return before its consumers are down. Nor the
     * wait for its consumers to start and the timeout it can give up on - {@code consumerStartTimeout},
     * a minute by default in this library - which the model skips by never waiting at all. Nothing here
     * asserts on any of those.</p>
     *
     * <p>Two gaps are worth naming because the production code and these tests lean on what fills them.
     * The first is that there is no {@code consumers} field: the real container's is written only by
     * {@code initializeConsumers} and by the shutdown callback that nulls it, and {@code doStart()} throws
     * {@code "A stopped container should not have consumers"} when it is non-null - a hazard the
     * production javadoc gives as a reason for stopping, and one this model cannot be put into at all.
     * The second is {@link #willFailToStop}: it hands the failure straight back, while the real
     * {@code stop()} puts a non-AMQP failure through {@code convertRabbitAccessException}, which ends in
     * an {@code UncategorizedAmqpException} carrying only the cause - so the message the caller sees for
     * {@code "the container could not be stopped"} would be prefixed, and the exact-message assertion on
     * the suppressed exception below is a statement about this model rather than about the container. A
     * real stop can also fail from inside its cancellation loop, before the callback that nulls
     * {@code consumers} and before {@code initialized} is cleared - a state that makes the next start
     * throw the message above, and one the model cannot reach either.</p>
     *
     * <p>Nor does {@link #reportsItselfRunning()} read Spring AMQP's own flag: {@code isRunning()} is
     * final and reads a private field that only {@code doStart()} raises - which a subclass cannot reach
     * without dragging in the whole container startup - so the model keeps its own copy of it. It is the
     * same flag Spring AMQP's early return tests, and it is deliberately not named {@code isRunning()},
     * which every instance of this class answers {@code false} to whatever state it is in.</p>
     */
    static final class StatefulListenerContainer extends SimpleMessageListenerContainer {

        /** How a start that is not skipped goes, as one of the shapes a real one can take. */
        enum StartOutcome {
            /** It works: the flag goes up and a consumer comes with it. */
            STARTS_A_CONSUMER,
            /**
             * The half-failure: the flag goes up - Spring AMQP raises it before its consumers are started
             * - and nothing consumes, which is the state every later start returns early out of.
             */
            LEAVES_NO_CONSUMER,
            /**
             * It throws after the flag went up, which is what the real wait for its consumers does
             * ({@code AmqpIllegalStateException("Fatal exception on listener startup")}).
             */
            THROWS
        }

        private StartOutcome outcome = StartOutcome.STARTS_A_CONSUMER;
        private RuntimeException stopFailure;
        private boolean running;
        private int consumers;
        private int attempts;
        private int skipped;
        private int stops;
        private Map<String, Object> consumerArguments;

        @Override
        public void start() {
            if (running) {
                // Spring AMQP's own first line: a container that reports itself running is not started
                // again. A start that half-failed leaves exactly this flag up with no consumer behind
                // it, so every later start would return here having started nothing.
                skipped++;
                return;
            }
            attempts++;
            // Raised before the consumers, as SimpleMessageListenerContainer.doStart() raises it: it
            // calls its superclass first and starts its consumers afterwards.
            running = true;
            switch (outcome) {
                case STARTS_A_CONSUMER -> consumers = 1;
                case LEAVES_NO_CONSUMER -> consumers = 0;
                case THROWS -> throw new AmqpIllegalStateException("Fatal exception on listener startup");
            }
        }

        @Override
        public void stop() {
            stops++;
            // Lowered in a finally by the real one, so it is down even when the stop throws - which is
            // what makes stop the call that can always take a container back out of a half-started state.
            running = false;
            consumers = 0;
            RuntimeException failure = stopFailure;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public int getActiveConsumerCount() {
            return consumers;
        }

        @Override
        public void setConsumerArguments(Map<String, Object> consumerArguments) {
            this.consumerArguments = consumerArguments;
        }

        /** The broker has recovered, or never had a problem: the next start brings a consumer. */
        StatefulListenerContainer willStartAConsumer() {
            return withOutcome(StartOutcome.STARTS_A_CONSUMER);
        }

        /** The next start leaves the flag up and nothing consuming. */
        StatefulListenerContainer willLeaveNoConsumer() {
            return withOutcome(StartOutcome.LEAVES_NO_CONSUMER);
        }

        /** The next start throws after the flag went up. */
        StatefulListenerContainer willFailToStart() {
            return withOutcome(StartOutcome.THROWS);
        }

        /** The container's own stop is broken; the flag still comes down. */
        StatefulListenerContainer willFailToStop(RuntimeException failure) {
            this.stopFailure = failure;
            return this;
        }

        /** The flag Spring AMQP's early return tests, which {@code isRunning()} cannot be asked for here. */
        boolean reportsItselfRunning() {
            return running;
        }

        /** Starts that got past the early return. */
        int attempts() {
            return attempts;
        }

        /** Starts the early return swallowed, which is what a container left half-started produces. */
        int skippedStarts() {
            return skipped;
        }

        int stops() {
            return stops;
        }

        Map<String, Object> consumerArguments() {
            return consumerArguments;
        }

        private StatefulListenerContainer withOutcome(StartOutcome next) {
            this.outcome = next;
            return this;
        }
    }
}
