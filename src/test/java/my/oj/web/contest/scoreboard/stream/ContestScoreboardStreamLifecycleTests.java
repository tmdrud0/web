package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a restarted consumer asks the broker to begin, and what makes it restart at all.
 *
 * <p>The startup decision is not cosmetic: resuming at the stored checkpoint skips everything already
 * applied, while starting at the beginning of retention re-reads it. Both are correct - the script
 * returns early for any offset at or below the checkpoint - but only one of them is what an operator
 * wants when the checkpoint itself is suspect, so the two must not be the same code path with the
 * same argument.</p>
 *
 * <p>The supervisor pass has two causes to answer, and the second one is not a rollback: a failed
 * batch leaves the checkpoint exactly where it was, so the rollback guard sees nothing wrong while
 * the batch is never applied. Nothing at the broker brings it back either - a stream queue accepts a
 * requeueing rejection and does not redeliver - so the pass is the only thing that re-reads it.</p>
 */
class ContestScoreboardStreamLifecycleTests {

    private SimpleMessageListenerContainer container;
    private ContestScoreboardApplier applier;
    private ContestScoreboardStreamListener listener;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        container = mock(SimpleMessageListenerContainer.class);
        applier = mock(ContestScoreboardApplier.class);
        listener = mock(ContestScoreboardStreamListener.class);
        registry = new SimpleMeterRegistry();
    }

    @Test
    void consumptionResumesJustAfterTheStoredCheckpoint() {
        when(applier.currentStreamOffset()).thenReturn(4L);

        lifecycle(StartupOffset.STORED).start();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 5L);
        verify(listener).initializeOffset(4L);
    }

    @Test
    void theFirstRetainedOffsetIsUsedWhenTheCheckpointIsTheThingInDoubt() {
        when(applier.currentStreamOffset()).thenReturn(4L);

        lifecycle(StartupOffset.FIRST).start();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", "first");
        // The checkpoint is still the scoreboard's own value: re-reading retention must not rewrite
        // it, or the first re-delivered message would look like a forward jump to the script.
        verify(listener).initializeOffset(4L);
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
        when(listener.highestAppliedOffset()).thenReturn(4L);
        when(listener.failedBatches()).thenReturn(1L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.recoverConsumption();

        // One start for the service coming up, one for the resubscribe that re-reads the batch.
        verify(container, times(2)).start();
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isZero();
    }

    /**
     * The distinction between the two causes, and why they are not one branch. A failure resumes at
     * the next offset because this process applied everything up to the checkpoint; only on the way up
     * does {@code startup-offset=first} distrust it. Honouring {@code first} here would re-read all of
     * retention to recover one batch.
     */
    @Test
    void aFailedBatchResumesAtTheNextOffsetEvenWhenStartupDistrustsTheCheckpoint() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(listener.highestAppliedOffset()).thenReturn(4L);
        when(listener.failedBatches()).thenReturn(2L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.FIRST);
        lifecycle.start();
        assertThat(consumerArguments()).containsEntry("x-stream-offset", "first");

        lifecycle.recoverConsumption();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 5L);
    }

    /** A failure already answered by a resubscribe must not restart the consumer on every pass. */
    @Test
    void aFailureIsAnsweredOnce() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(listener.highestAppliedOffset()).thenReturn(4L);
        when(listener.failedBatches()).thenReturn(1L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.recoverConsumption();
        lifecycle.recoverConsumption();

        verify(container, times(2)).start();
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isEqualTo(1.0);
    }

    @Test
    void aRollbackBehindAnAppliedOffsetIsStillRestarted() {
        when(applier.currentStreamOffset()).thenReturn(2L);
        when(listener.highestAppliedOffset()).thenReturn(4L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.recoverConsumption();

        assertThat(consumerArguments()).containsEntry("x-stream-offset", 3L);
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isZero();
    }

    /** A healthy consumer must be left alone: this pass runs on an interval, all day. */
    @Test
    void aConsumerInStepWithTheScoreboardIsLeftAlone() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(listener.highestAppliedOffset()).thenReturn(4L);
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(StartupOffset.STORED);
        lifecycle.start();

        lifecycle.recoverConsumption();

        verify(container, times(1)).start();
    }

    private ContestScoreboardStreamLifecycle lifecycle(StartupOffset startupOffset) {
        return new ContestScoreboardStreamLifecycle(
                container,
                applier,
                mock(ContestScoreboardAppliedAtCompletion.class),
                listener,
                new ContestScoreboardStreamMetrics(registry),
                properties(startupOffset)
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
        );
    }
}
