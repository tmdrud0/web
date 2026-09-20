package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
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
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        container = mock(SimpleMessageListenerContainer.class);
        applier = mock(ContestScoreboardApplier.class);
        position = new ContestScoreboardStreamPosition();
        strategy = mock(ContestScoreboardRecoveryStrategy.class);
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

    /** A failure already answered by a resubscribe must not restart the consumer on every pass. */
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
        when(strategy.rebuildHistory(any())).thenReturn(true);
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
        when(strategy.rebuildHistory(any())).thenReturn(true);
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
     * A rebuild that failed must not be recorded as done: the live path reads that record to decide
     * whether it may anchor past the range, and a false record there would move the checkpoint over
     * results nobody put back.
     */
    @Test
    void aRollbackTheModeCouldNotRebuildIsNotRecordedAsDone() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(false);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();

        assertThat(position.rebuiltThrough()).isEqualTo(-1L);
        assertThat(counter("contest.scoreboard.stream.rollback.observed")).isEqualTo(1.0);
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
        when(strategy.rebuildHistory(any())).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(strategy, times(1)).rebuildHistory(any());
        assertThat(counter("contest.scoreboard.stream.rollback.observed")).isEqualTo(1.0);
    }

    /** A consumer that was restarted is a new position, so the rollback must be judged again. */
    @Test
    void aFurtherRollbackIsAnsweredEvenAfterAnEarlierOne() {
        when(applier.currentStreamOffset()).thenReturn(4L, 2L, 1L);
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.rebuildHistory(any())).thenReturn(true);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();
        position.recordAppliedOffset(4L);

        lifecycle.recoverConsumption();
        position.recordAppliedOffset(2L);
        lifecycle.recoverConsumption();

        verify(strategy, times(2)).rebuildHistory(any());
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
                strategy
        );
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
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
