package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.CheckpointAdvance;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the checkpoint is allowed to claim about a delivery, now that offsets are not consecutive.
 *
 * <p>The contract these pin: a consumer handed offset {@code K} re-reads from {@code K} inclusive, so
 * the first delivery is normally {@code K} itself - an already-applied duplicate that anchors the
 * position rather than a gap. Nothing here may treat {@code K + 2} as a gap, and nothing may move the
 * checkpoint past a range no basis rebuilt.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardStreamProcessorTests {

    @Mock
    private ContestScoreboardApplier applier;
    @Mock
    private ContestScoreboardAppliedAtCompletion completion;
    @Mock
    private ContestScoreboardRecoveryStrategy strategy;

    private ContestScoreboardStreamPosition position;
    private SimpleMeterRegistry registry;
    private ContestScoreboardStreamProcessor processor;

    @BeforeEach
    void setUp() {
        position = new ContestScoreboardStreamPosition();
        registry = new SimpleMeterRegistry();
        ContestScoreboardStreamMetrics metrics = new ContestScoreboardStreamMetrics(registry);
        metrics.initializeOffset(4L);
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.STREAM_OFFSET);
        processor = new ContestScoreboardStreamProcessor(
                applier,
                completion,
                position,
                strategy,
                metrics,
                new ContestScoreboardApplyLock()
        );
    }

    /**
     * The contract in one batch: the checkpoint is re-read, then offsets 5, 7 and 12 arrive with two
     * of them missing. Not one of them is a gap - the position was anchored by the re-read of 4, and
     * past that point only monotonic increase is required.
     */
    @Test
    void sparseOffsetsFromAnAnchoredPositionAreNotMistakenForAGap() {
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 12L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));

        long applied = processor.process(List.of(event(4L, 104L), event(5L, 105L), event(7L, 107L),
                event(12L, 112L)));

        assertThat(applied).isEqualTo(12L);
        assertThat(requests().stream().map(ContestScoreboardApplier.ApplyRequest::streamOffset))
                .containsExactly(4L, 5L, 7L, 12L);
        assertThat(requests()).noneMatch(request -> request.advance() == CheckpointAdvance.ANCHOR);
        verify(strategy, never()).rebuildHistory(any());
        assertThat(counter("contest.scoreboard.stream.offset.gaps")).isZero();
        verify(completion).complete(List.of(104L, 105L, 107L, 112L));
        // 4 is at the checkpoint the metric already recorded, so only 5, 7 and 12 are new.
        assertThat(counter("contest.scoreboard.applied")).isEqualTo(3.0);
    }

    /** The stored offset re-delivered on a resubscribe anchors the position and changes nothing. */
    @Test
    void aResubscribeAnchorIsAcceptedAsThePosition() {
        when(applier.currentStreamOffset()).thenReturn(5L, 5L, 9L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));

        processor.process(List.of(event(5L, 105L), event(9L, 109L)));

        assertThat(position.anchorVerified()).isTrue();
        verify(strategy, never()).rebuildHistory(any());
    }

    /**
     * The first delivery above an unretained checkpoint is the one case where the mode's basis has to
     * answer, and only its answer lets the checkpoint move - and then only to the offset that arrived,
     * never to {@code checkpoint + 1}.
     */
    @Test
    void aDeliveryAboveAnUnretainedCheckpointIsAnchoredOnlyAfterTheBasisRebuiltIt() {
        when(applier.currentStreamOffset()).thenReturn(5L, 5L, 11L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        when(strategy.rebuildHistory(any())).thenReturn(true);
        position.resumeAt(5L);

        processor.process(List.of(event(10L, 110L), event(11L, 111L)));

        assertThat(requests().stream().map(ContestScoreboardApplier.ApplyRequest::advance))
                .containsExactly(CheckpointAdvance.ANCHOR, CheckpointAdvance.CONTINUE);
        ContestScoreboardRecoveryStrategy.LostRange range = capturedRange();
        assertThat(range.checkpointOffset()).isEqualTo(5L);
        assertThat(range.firstLostOffset()).isEqualTo(6L);
        assertThat(range.highestAppliedOffset()).isEqualTo(5L);
        assertThat(counter("contest.scoreboard.stream.offset.gaps")).isEqualTo(1.0);
    }

    /**
     * The refusal, and the point of the whole contract: a range no basis rebuilt must leave the batch
     * unapplied so the checkpoint stays where it was. Moving it would put the standings past results
     * nobody put back, which is the loss the checkpoint exists to prevent.
     */
    @Test
    void aRetentionGapNoBasisRebuiltLeavesTheBatchUnapplied() {
        when(applier.currentStreamOffset()).thenReturn(5L);
        when(strategy.rebuildHistory(any())).thenReturn(false);
        position.resumeAt(5L);

        assertThatThrownBy(() -> processor.process(List.of(event(10L, 110L), event(11L, 111L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint does not move past results the standings never saw");

        verify(applier, never()).applyAll(anyList());
        verify(completion, never()).complete(anyList());
        assertThat(counter("contest.scoreboard.stream.offset.gaps")).isEqualTo(1.0);
    }

    /**
     * No checkpoint at all: the first offset that exists becomes the anchor, whatever number it
     * carries. A stream is not required to begin at zero or one, and a mode that assumed it would
     * refuse a perfectly ordinary stream.
     */
    @Test
    void aScoreboardWithNoCheckpointAdoptsTheFirstOffsetItIsHanded() {
        when(applier.currentStreamOffset()).thenReturn(-1L, -1L, 7L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));

        processor.process(List.of(event(7L, 107L), event(8L, 108L)));

        assertThat(requests().stream().map(ContestScoreboardApplier.ApplyRequest::advance))
                .containsExactly(CheckpointAdvance.ANCHOR, CheckpointAdvance.CONTINUE);
        assertThat(position.anchorVerified()).isTrue();
        verify(strategy, never()).rebuildHistory(any());
        // Nothing was asked for and withheld: no offset was outside retention.
        assertThat(counter("contest.scoreboard.stream.offset.gaps")).isZero();
    }

    /**
     * A rollback seen by the live path before the supervisor's next pass is not a retention gap. The
     * offset is retained; the scoreboard is what moved. Counting it as a gap would report a broker
     * that is keeping its retention perfectly well, and the range handed to the mode must describe
     * what the rollback took away rather than what the stream can no longer serve.
     */
    @Test
    void aRollbackIsCountedApartFromARetentionGapAndJudgedAfresh() {
        when(applier.currentStreamOffset()).thenReturn(2L, 2L, 5L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        when(strategy.rebuildHistory(any())).thenReturn(true);
        position.resumeAt(4L);
        // The anchor was verified for the position the consumer held before Redis rolled back.
        position.markAnchorVerified();

        processor.process(List.of(event(5L, 105L)));

        ContestScoreboardRecoveryStrategy.LostRange range = capturedRange();
        assertThat(range.checkpointOffset()).isEqualTo(2L);
        assertThat(range.firstLostOffset()).isEqualTo(3L);
        assertThat(range.highestAppliedOffset()).isEqualTo(4L);
        assertThat(counter("contest.scoreboard.stream.offset.gaps")).isZero();
        assertThat(requests().get(0).advance()).isEqualTo(CheckpointAdvance.ANCHOR);
    }

    /**
     * What a completed rebuild reached is handed to the mode, so a range the supervisor already
     * rebuilt is not rebuilt a second time by the delivery that anchors past it.
     */
    @Test
    void theRangeCarriesHowFarACompletedRebuildAlreadyReached() {
        when(applier.currentStreamOffset()).thenReturn(5L, 5L, 10L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        when(strategy.rebuildHistory(any())).thenReturn(true);
        position.resumeAt(5L);
        position.markRebuiltThrough(9L);

        processor.process(List.of(event(10L, 110L)));

        assertThat(capturedRange().rebuiltThrough()).isEqualTo(9L);
    }

    @Test
    void failedRedisApplyDoesNotCompleteMysqlOrCountAppliedEvents() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(applier.applyAll(anyList())).thenReturn(List.of(
                ContestScoreboardApplier.ApplyResult.failure(5L, "Redis unavailable")
        ));

        assertThatThrownBy(() -> processor.process(List.of(event(4L, 104L), event(5L, 105L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis unavailable");

        verify(completion, never()).complete(anyList());
        assertThat(counter("contest.scoreboard.applied")).isZero();
    }

    /**
     * What a failed apply leaves behind is a range, and it starts at the offset the applier stopped at -
     * the events before it in the batch were applied, so the standings end below that one.
     */
    @Test
    void aFailedApplyIsRecordedAsTheRangeTheCheckpointMayNotPass() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(applier.applyAll(anyList())).thenReturn(List.of(
                ContestScoreboardApplier.ApplyResult.success(4L, 4L),
                ContestScoreboardApplier.ApplyResult.failure(5L, "Redis unavailable")
        ));

        assertThatThrownBy(() -> processor.process(List.of(event(4L, 104L), event(5L, 105L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(position.unappliedFrom()).isEqualTo(5L);
    }

    /**
     * The delivery after a failed batch, with nothing in Redis to ask about: the scoreboard holds no
     * checkpoint, so there is no range below the delivery to hand a mode. "Nothing to be discontinuous
     * with" was read as licence to adopt this offset as the checkpoint - and the offset the batch failed
     * at, which the stream will not serve again, was stepped over: the standings lost it with the
     * failure as the only record.
     */
    @Test
    void aDeliveryAboveARangeAFailedBatchLeftUnappliedIsRefusedWhenThereIsNoCheckpoint() {
        when(applier.currentStreamOffset()).thenReturn(-1L);
        position.recordUnappliedRange(0L);

        assertThatThrownBy(() -> processor.process(List.of(event(1L, 101L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("starts above offset 0")
                .hasMessageContaining("nothing rebuilt")
                .hasMessageContaining("does not move past results the standings never saw");

        verify(applier, never()).applyAll(anyList());
        verify(completion, never()).complete(anyList());
        verify(strategy, never()).rebuildHistory(any());
        assertThat(counter("contest.scoreboard.stream.unapplied.refusals")).isEqualTo(1.0);
    }

    /** The way out is the delivery that starts at the range, and applying it is what releases it. */
    @Test
    void aDeliveryThatStartsAtTheUnappliedRangeIsAppliedAndReleasesIt() {
        when(applier.currentStreamOffset()).thenReturn(-1L, -1L, 2L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        position.recordUnappliedRange(0L);

        long applied = processor.process(List.of(event(0L, 100L), event(2L, 102L)));

        assertThat(applied).isEqualTo(2L);
        assertThat(position.unappliedFrom()).isEqualTo(-1L);
        assertThat(counter("contest.scoreboard.stream.unapplied.refusals")).isZero();
    }

    /**
     * With a checkpoint there is a range below the delivery and a mode that owns it, so the range is not
     * refused here but asked about - which means the delivery must not be carried forward as an ordinary
     * step, whatever the anchor had established.
     */
    @Test
    void aDeliveryAboveTheRangeAsksTheModeWhenThereIsACheckpoint() {
        when(applier.currentStreamOffset()).thenReturn(5L);
        when(strategy.rebuildHistory(any())).thenReturn(false);
        position.resumeAt(5L);
        position.markAnchorVerified();
        position.recordUnappliedRange(6L);

        assertThatThrownBy(() -> processor.process(List.of(event(7L, 107L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint does not move past results the standings never saw");

        assertThat(capturedRange().firstLostOffset()).isEqualTo(6L);
        assertThat(counter("contest.scoreboard.stream.offset.gaps")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.unapplied.refusals")).isZero();
        // Being asked about does not clear it: only the delivery that applies the range does.
        assertThat(position.unappliedFrom()).isEqualTo(6L);
        verify(applier, never()).applyAll(anyList());
    }

    @Test
    void successfulRetryCountsPartiallyAppliedOffsetsButAckRedeliveryDoesNotCountTwice() {
        when(applier.currentStreamOffset()).thenReturn(5L, 5L, 6L, 5L, 5L, 6L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        List<ContestScoreboardStreamEvent> events = List.of(event(5L, 105L), event(6L, 106L));

        processor.process(events);
        processor.process(events);

        assertThat(counter("contest.scoreboard.applied")).isEqualTo(2.0);
    }

    private static List<ContestScoreboardApplier.ApplyResult> success(
            List<ContestScoreboardApplier.ApplyRequest> requests) {
        return requests.stream()
                .map(request -> ContestScoreboardApplier.ApplyResult.success(
                        request.correlationId(), request.streamOffset()))
                .toList();
    }

    private List<ContestScoreboardApplier.ApplyRequest> requests() {
        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> captured = requestsCaptor();
        verify(applier).applyAll(captured.capture());
        return captured.getValue();
    }

    private ContestScoreboardRecoveryStrategy.LostRange capturedRange() {
        ArgumentCaptor<ContestScoreboardRecoveryStrategy.LostRange> range =
                ArgumentCaptor.forClass(ContestScoreboardRecoveryStrategy.LostRange.class);
        verify(strategy).rebuildHistory(range.capture());
        return range.getValue();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private static ContestScoreboardStreamEvent event(long offset, long submissionId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        return new ContestScoreboardStreamEvent(offset, new ContestJudgeResultStreamMessage(
                ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                submissionId,
                10L,
                20L,
                30L,
                now.minusHours(1),
                now.minusMinutes(1),
                now,
                SubmissionResult.ACCEPTED
        ));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requestsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
