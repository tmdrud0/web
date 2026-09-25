package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.LiveEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryEvent;
import my.oj.web.contest.scoreboard.experiment.RecordingExperimentTrace;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.Outcome;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardTouchedContests;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live-apply trace: what it records for an applied batch, what it leaves out, and that turning it
 * off changes nothing the processor does.
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardStreamProcessorTraceTests {

    private static final LocalDateTime JUDGED_AT = LocalDateTime.of(2026, 9, 25, 12, 0, 0, 250_000_000);

    @Mock
    private ContestScoreboardApplier applier;
    @Mock
    private ContestScoreboardAppliedAtCompletion completion;
    @Mock
    private ContestScoreboardRecoveryStrategy strategy;

    @Test
    void anAppliedBatchIsRecordedEventByEventWithOneApplyInstant() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 9L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        long before = System.currentTimeMillis();

        processor(trace).process(List.of(event(4L, 104L), event(7L, 107L), event(9L, 109L)));

        assertThat(trace.liveBatches()).hasSize(1);
        RecordingExperimentTrace.LiveBatch batch = trace.liveBatches().get(0);
        assertThat(batch.appliedAtEpochMillis()).isBetween(before, System.currentTimeMillis());
        // Every delivered event, the re-delivered anchor included: which of them is "new" is the
        // summarizer's decision against the pre-rollback offset, not the processor's.
        assertThat(batch.events()).containsExactly(
                new LiveEvent(4L, 104L, JUDGED_AT),
                new LiveEvent(7L, 107L, JUDGED_AT),
                new LiveEvent(9L, 109L, JUDGED_AT));
    }

    @Test
    void aBatchTheApplierRefusedIsNotRecorded() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        when(applier.currentStreamOffset()).thenReturn(4L, 4L);
        when(applier.applyAll(anyList())).thenReturn(List.of(
                ContestScoreboardApplier.ApplyResult.success(4L, 4L),
                ContestScoreboardApplier.ApplyResult.failure(5L, "refused")));

        assertThatThrownBy(() -> processor(trace).process(List.of(event(4L, 104L), event(5L, 105L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(trace.liveBatches()).isEmpty();
    }

    @Test
    void aGapQuestionIsRecordedWithTheModesAnswerEvenWhenItRefuses() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.FULL_REPLAY);
        when(applier.currentStreamOffset()).thenReturn(5L);
        when(strategy.rebuildHistory(any())).thenReturn(Outcome.BUSY_RETRY_LATER);
        ContestScoreboardStreamPosition position = new ContestScoreboardStreamPosition();
        // A rollback: this JVM applied through 20 and Redis now says 5.
        position.recordAppliedOffset(20L);
        ContestScoreboardStreamProcessor processor = processor(trace, position);

        assertThatThrownBy(() -> processor.process(List.of(event(30L, 130L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(trace.liveBatches()).isEmpty();
        assertThat(trace.recoveryEvents()).containsExactly(RecoveryEvent.GAP);
        ContestScoreboardExperimentTrace.RecoveryRecord gap = trace.recoveryRecords().get(0);
        assertThat(gap.outcome()).isEqualTo("busy-retry-later");
        assertThat(gap.detail()).isEqualTo("rollback checkpoint=5 delivery=30");
        assertThat(gap.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(gap.endEpochMillis()).isGreaterThanOrEqualTo(gap.startEpochMillis());
    }

    /**
     * The same deliveries through a traced and an untraced processor reach the applier as the same
     * requests and leave the same checkpoint. The trace observes; it does not take part.
     */
    @Test
    void theTraceChangesNothingTheProcessorSendsOrReturns() {
        List<List<ContestScoreboardApplier.ApplyRequest>> sent = new ArrayList<>();
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 12L, 4L, 4L, 12L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            sent.add(requests);
            return success(requests);
        });
        List<ContestScoreboardStreamEvent> batch = List.of(event(4L, 104L), event(5L, 105L), event(12L, 112L));

        long untraced = processor(ContestScoreboardExperimentTrace.NOOP).process(batch);
        long traced = processor(new RecordingExperimentTrace()).process(batch);

        assertThat(traced).isEqualTo(untraced);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(sent.get(0));
        ArgumentCaptor<List<Long>> completed = submissionIdsCaptor();
        verify(completion, times(2)).complete(completed.capture());
        assertThat(completed.getAllValues().get(1)).isEqualTo(completed.getAllValues().get(0));
    }

    /** What a later rollback is repaired for: each applied contest, at the offset that wrote it. */
    @Test
    void anAppliedBatchStampsItsContestWithTheHighestOffsetItApplied() {
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 9L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        ContestScoreboardTouchedContests touched = new ContestScoreboardTouchedContests();
        ContestScoreboardStreamMetrics metrics = new ContestScoreboardStreamMetrics(new SimpleMeterRegistry());
        metrics.initializeOffset(4L);
        ContestScoreboardStreamProcessor processor = new ContestScoreboardStreamProcessor(applier, completion,
                new ContestScoreboardStreamPosition(), strategy, metrics, new ContestScoreboardApplyLock(),
                ContestScoreboardExperimentTrace.NOOP, touched);

        processor.process(List.of(event(4L, 104L), event(9L, 109L)));

        assertThat(touched.touchedAtOrAbove(9L)).containsExactly(10L);
        assertThat(touched.touchedAtOrAbove(10L)).isEmpty();
    }

    private ContestScoreboardStreamProcessor processor(ContestScoreboardExperimentTrace trace) {
        return processor(trace, new ContestScoreboardStreamPosition());
    }

    private ContestScoreboardStreamProcessor processor(ContestScoreboardExperimentTrace trace,
                                                       ContestScoreboardStreamPosition position) {
        ContestScoreboardStreamMetrics metrics = new ContestScoreboardStreamMetrics(new SimpleMeterRegistry());
        metrics.initializeOffset(4L);
        return new ContestScoreboardStreamProcessor(
                applier,
                completion,
                position,
                strategy,
                metrics,
                new ContestScoreboardApplyLock(),
                trace
        );
    }

    private static List<ContestScoreboardApplier.ApplyResult> success(
            List<ContestScoreboardApplier.ApplyRequest> requests) {
        return requests.stream()
                .map(request -> ContestScoreboardApplier.ApplyResult.success(
                        request.correlationId(), request.streamOffset()))
                .toList();
    }

    private static ContestScoreboardStreamEvent event(long offset, long submissionId) {
        return new ContestScoreboardStreamEvent(offset, new ContestJudgeResultStreamMessage(
                ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                submissionId,
                10L,
                20L,
                30L,
                JUDGED_AT.minusHours(1),
                JUDGED_AT.minusMinutes(1),
                JUDGED_AT,
                SubmissionResult.ACCEPTED
        ));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Long>> submissionIdsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
