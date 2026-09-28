package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryCutover;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the consumer does with a batch the checkpoint CAS refused: which floor it sends, that a refusal
 * is a rollback and not a failed batch, and that the rollback is answered at once and only once.
 */
class ContestScoreboardStreamCheckpointCasTests {

    private ContestScoreboardApplier applier;
    private ContestScoreboardAppliedAtCompletion completion;
    private ContestScoreboardRecoveryStrategy strategy;
    private ContestScoreboardStreamPosition position;
    private SimpleMeterRegistry registry;
    private ContestScoreboardStreamMetrics metrics;
    private ContestScoreboardStreamProcessor processor;

    @BeforeEach
    void setUp() {
        applier = mock(ContestScoreboardApplier.class);
        completion = mock(ContestScoreboardAppliedAtCompletion.class);
        strategy = mock(ContestScoreboardRecoveryStrategy.class);
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.STREAM_OFFSET);
        lenient().when(strategy.rewindsOnCheckpointRegression()).thenReturn(true);
        position = new ContestScoreboardStreamPosition();
        registry = new SimpleMeterRegistry();
        metrics = new ContestScoreboardStreamMetrics(registry);
        processor = new ContestScoreboardStreamProcessor(applier, completion, position, strategy, metrics,
                new ContestScoreboardApplyLock());
    }

    // --- the floor ------------------------------------------------------------------------------------

    @Test
    void theFloorIsTheCheckpointThePositionWasVerifiedAtAndThenWhatBatchesLeft() {
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 7L, 7L, 7L, 9L);
        List<Long> floors = new ArrayList<>();
        when(applier.applyAll(anyList(), anyLong())).thenAnswer(invocation -> {
            floors.add(invocation.getArgument(1));
            return succeed(invocation.getArgument(0));
        });

        processor.process(List.of(event(4L), event(5L), event(7L)));
        processor.process(List.of(event(8L), event(9L)));

        // Verified at the re-read checkpoint 4, then raised by the batch that left 7.
        assertThat(floors).containsExactly(4L, 7L);
    }

    @Test
    void aFirstStartWithNoCheckpointSendsNoFloor() {
        when(applier.currentStreamOffset()).thenReturn(-1L, -1L, 3L);
        when(applier.applyAll(anyList(), anyLong())).thenAnswer(invocation -> succeed(invocation.getArgument(0)));

        processor.process(List.of(event(3L)));

        verify(applier).applyAll(anyList(), eq(ContestScoreboardApplier.NO_CHECKPOINT_FLOOR));
    }

    @Test
    void aResubscribeStartsTheFloorAgainFromTheCheckpointItResumedAt() {
        when(applier.currentStreamOffset()).thenReturn(19L, 19L, 29L, 9L, 9L, 29L);
        List<Long> floors = new ArrayList<>();
        when(applier.applyAll(anyList(), anyLong())).thenAnswer(invocation -> {
            floors.add(invocation.getArgument(1));
            return succeed(invocation.getArgument(0));
        });
        processor.process(List.of(event(19L), event(29L)));

        // Redis went back to 9; the lifecycle resubscribed there.
        position.consumerRestarted();
        processor.process(List.of(event(9L), event(10L), event(29L)));

        // Not 29: a floor that never came down would refuse the very re-read that repairs the rollback.
        assertThat(floors).containsExactly(19L, 9L);
        assertThat(position.highestAppliedOffset()).isEqualTo(29L);
    }

    // --- a refusal is a rollback, not a failure ------------------------------------------------------

    @Test
    void aRefusedBatchIsNeitherAFailedBatchNorAnUnappliedRange() {
        when(applier.currentStreamOffset()).thenReturn(19L);
        position.anchorAt(19L);
        when(applier.applyAll(anyList(), anyLong())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return List.of(ContestScoreboardApplier.ApplyResult.rollback(requests.get(0).correlationId(), 9L));
        });

        assertThatThrownBy(() -> processor.process(List.of(event(20L), event(21L))))
                .isInstanceOfSatisfying(ContestScoreboardCheckpointRegressedException.class, refused -> {
                    assertThat(refused.expectedFloor()).isEqualTo(19L);
                    assertThat(refused.storedCheckpoint()).isEqualTo(9L);
                    assertThat(refused.consumerGeneration()).isEqualTo(position.consumerGeneration());
                });

        assertThat(position.unappliedFrom()).isEqualTo(-1L);
        assertThat(position.failedBatches()).isZero();
        assertThat(position.anchorVerified()).isFalse();
        assertThat(position.highestAppliedOffset()).isEqualTo(-1L);
        verifyNoInteractions(completion);
        assertThat(registry.get("contest.scoreboard.stream.checkpoint.regressed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void theListenerAsksForTheRollbackAnswerWithoutCountingAFailure() {
        MessageConverter converter = mock(MessageConverter.class);
        when(converter.fromMessage(any())).thenReturn(payload(20L));
        ContestScoreboardStreamProcessor refusing = mock(ContestScoreboardStreamProcessor.class);
        when(refusing.process(anyList())).thenThrow(new ContestScoreboardCheckpointRegressedException(19L, 9L, 3L));
        List<long[]> signals = new ArrayList<>();
        ContestScoreboardStreamRollbackSignal signal = new ContestScoreboardStreamRollbackSignal();
        signal.register((generation, floor) -> signals.add(new long[]{generation, floor}));
        position.markAnchorVerified();
        ContestScoreboardStreamListener listener = new ContestScoreboardStreamListener(converter, refusing, position,
                metrics, consumerProperties(), signal);

        assertThatThrownBy(() -> listener.onMessageBatch(List.of(message(20L))))
                .isInstanceOf(ImmediateRequeueAmqpException.class);

        assertThat(signals).singleElement().satisfies(values -> assertThat(values).containsExactly(3L, 19L));
        assertThat(position.failedBatches()).isZero();
        assertThat(position.unappliedFrom()).isEqualTo(-1L);
        assertThat(position.anchorVerified()).isFalse();
        assertThat(registry.get("contest.scoreboard.stream.failures").counter().count()).isZero();
    }

    // --- the answer: immediate, and once ------------------------------------------------------------

    @Test
    void aRefusalResubscribesAtTheStoredCheckpointWithoutWaitingForTheSupervisor() {
        SimpleMessageListenerContainer container = consumingContainer();
        ContestScoreboardStreamRollbackSignal signal = new ContestScoreboardStreamRollbackSignal();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, signal);
        when(applier.currentStreamOffset()).thenReturn(19L);
        lifecycle.start();
        position.recordAppliedOffset(19L);
        long generation = position.consumerGeneration();

        when(applier.currentStreamOffset()).thenReturn(9L);
        signal.checkpointRegressed(generation, 19L);

        // One start for the service, one for the resubscribe - and no supervisor pass ran.
        verify(container, times(2)).start();
        verify(container).setConsumerArguments(java.util.Map.of("x-stream-offset", 9L));
        assertThat(detections("apply-cas")).isEqualTo(1.0);
        assertThat(detections("supervisor")).isZero();
        assertThat(registry.get("contest.scoreboard.stream.rollback.restarts").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aRollbackAnsweredByTheCasIsNotRestartedAgainByTheSupervisorOrALaterRefusal() {
        SimpleMessageListenerContainer container = consumingContainer();
        ContestScoreboardStreamRollbackSignal signal = new ContestScoreboardStreamRollbackSignal();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, signal);
        when(applier.currentStreamOffset()).thenReturn(19L);
        lifecycle.start();
        position.recordAppliedOffset(19L);
        long refusedAt = position.consumerGeneration();
        when(applier.currentStreamOffset()).thenReturn(9L);

        signal.checkpointRegressed(refusedAt, 19L);
        // A second batch of the old position, refused before the resubscribe landed.
        signal.checkpointRegressed(refusedAt, 19L);
        // The supervisor sees stored 9 < applied 19, the same pair the CAS already answered.
        lifecycle.recoverConsumption();

        verify(container, times(2)).start();
        assertThat(registry.get("contest.scoreboard.stream.rollback.restarts").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aRefusalFromAPositionTheSupervisorAlreadyLeftIsNotRestartedAgain() {
        SimpleMessageListenerContainer container = consumingContainer();
        ContestScoreboardStreamRollbackSignal signal = new ContestScoreboardStreamRollbackSignal();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, signal);
        when(applier.currentStreamOffset()).thenReturn(19L);
        lifecycle.start();
        position.recordAppliedOffset(19L);
        long refusedAt = position.consumerGeneration();
        when(applier.currentStreamOffset()).thenReturn(9L);

        lifecycle.recoverConsumption();
        signal.checkpointRegressed(refusedAt, 19L);

        verify(container, times(2)).start();
        assertThat(detections("supervisor")).isEqualTo(1.0);
        assertThat(detections("apply-cas")).isZero();
    }

    @Test
    void aModeThatDoesNotRewindRebuildsFromItsBasisAndKeepsTheConsumerRunning() {
        when(strategy.rewindsOnCheckpointRegression()).thenReturn(false);
        when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.FULL_REPLAY);
        when(strategy.rebuildHistory(any())).thenReturn(ContestScoreboardRecoveryStrategy.Outcome.COVERED);
        SimpleMessageListenerContainer container = consumingContainer();
        ContestScoreboardStreamRollbackSignal signal = new ContestScoreboardStreamRollbackSignal();
        ContestScoreboardStreamLifecycle lifecycle = lifecycle(container, signal);
        when(applier.currentStreamOffset()).thenReturn(19L);
        startedOutsideTheHold(lifecycle);
        position.recordAppliedOffset(19L);
        when(applier.currentStreamOffset()).thenReturn(9L);

        signal.checkpointRegressed(position.consumerGeneration(), 19L);

        verify(strategy).rebuildHistory(any());
        verify(container, times(1)).start();
        verify(container, never()).stop();
        assertThat(position.rebuiltThrough()).isEqualTo(19L);
        assertThat(detections("apply-cas")).isEqualTo(1.0);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private double detections(String path) {
        return registry.get("contest.scoreboard.stream.rollback.detected").tag("path", path).counter().count();
    }

    private void startedOutsideTheHold(ContestScoreboardStreamLifecycle lifecycle) {
        // full-replay holds the consumer until its startup pass reports the boundary; report it.
        when(strategy.recoversHistoryBeforeConsuming()).thenReturn(true);
        lifecycle.start();
        cutover.markCovered("test");
    }

    private final ContestScoreboardRecoveryCutover cutover = new ContestScoreboardRecoveryCutover();

    private ContestScoreboardStreamLifecycle lifecycle(SimpleMessageListenerContainer container,
                                                       ContestScoreboardStreamRollbackSignal signal) {
        return new ContestScoreboardStreamLifecycle(container, applier, completion, position, metrics,
                recoveryProperties(), strategy, cutover, signal, Runnable::run);
    }

    private static SimpleMessageListenerContainer consumingContainer() {
        SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
        when(container.getActiveConsumerCount()).thenReturn(1);
        return container;
    }

    private static List<ContestScoreboardApplier.ApplyResult> succeed(List<ContestScoreboardApplier.ApplyRequest> requests) {
        return requests.stream()
                .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), request.streamOffset()))
                .toList();
    }

    private static ContestScoreboardStreamEvent event(long offset) {
        return new ContestScoreboardStreamEvent(offset, payload(offset));
    }

    private static ContestJudgeResultStreamMessage payload(long offset) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        return new ContestJudgeResultStreamMessage(ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                100L + offset, 10L, 20L, 30L, now.minusHours(1), now.minusMinutes(1), now, SubmissionResult.ACCEPTED);
    }

    private static Message message(long offset) {
        MessageProperties properties = new MessageProperties();
        properties.getHeaders().put("x-stream-offset", offset);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    private static ContestScoreboardStreamConsumerProperties consumerProperties() {
        return new ContestScoreboardStreamConsumerProperties(500, 500, Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096);
    }

    private static ContestScoreboardRecoveryProperties recoveryProperties() {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.STREAM_OFFSET,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 500, 3,
                        Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED),
                new ContestScoreboardRecoveryProperties.RecoveryOwner(true));
    }
}
