package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardStreamProcessorTests {

    @Mock
    private ContestScoreboardApplier applier;
    @Mock
    private ContestScoreboardAppliedAtCompletion completion;
    @Mock
    private ContestScoreboardStreamRecoveryService recoveryService;

    private SimpleMeterRegistry registry;
    private ContestScoreboardStreamMetrics metrics;
    private ContestScoreboardStreamProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ContestScoreboardStreamConsumerProperties properties = properties();
        metrics = new ContestScoreboardStreamMetrics(registry, properties);
        metrics.initializeOffset(4L);
        processor = new ContestScoreboardStreamProcessor(
                applier,
                completion,
                recoveryService,
                metrics,
                new ContestScoreboardStreamProcessingLock()
        );
    }

    @Test
    void appliesInOffsetOrderThenCompletesDbBatch() {
        when(applier.currentStreamOffset()).thenReturn(4L, 6L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));

        long applied = processor.process(List.of(event(5L, 105L), event(6L, 106L)));

        assertThat(applied).isEqualTo(6L);
        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requests = requestsCaptor();
        verify(applier).applyAll(requests.capture());
        assertThat(requests.getValue()).extracting(ContestScoreboardApplier.ApplyRequest::streamOffset)
                .containsExactly(5L, 6L);
        assertThat(requests.getValue()).noneMatch(ContestScoreboardApplier.ApplyRequest::allowOffsetGap);
        verify(completion).complete(List.of(105L, 106L));
        verify(recoveryService, never()).recoverRetentionGap(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        assertThat(registry.get("contest.scoreboard.applied").counter().count()).isEqualTo(2.0);
    }

    @Test
    void retentionGapRebuildsBeforeOnlyTheFirstRetainedOffsetMayBridgeGap() {
        when(applier.currentStreamOffset()).thenReturn(4L, 11L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));

        processor.process(List.of(event(10L, 110L), event(11L, 111L)));

        verify(recoveryService).recoverRetentionGap(5L, 10L);
        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requests = requestsCaptor();
        verify(applier).applyAll(requests.capture());
        assertThat(requests.getValue()).extracting(ContestScoreboardApplier.ApplyRequest::allowOffsetGap)
                .containsExactly(true, false);
    }

    @Test
    void failedRedisApplyDoesNotCompleteMysqlOrCountAppliedEvents() {
        when(applier.currentStreamOffset()).thenReturn(4L);
        when(applier.applyAll(anyList())).thenReturn(List.of(
                ContestScoreboardApplier.ApplyResult.failure(5L, "Redis unavailable")
        ));

        assertThatThrownBy(() -> processor.process(List.of(event(5L, 105L), event(6L, 106L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis unavailable");

        verify(completion, never()).complete(anyList());
        assertThat(registry.get("contest.scoreboard.applied").counter().count()).isZero();
    }

    @Test
    void successfulRetryCountsPartiallyAppliedOffsetsButAckRedeliveryDoesNotCountTwice() {
        when(applier.currentStreamOffset()).thenReturn(5L, 6L, 6L, 6L);
        when(applier.applyAll(anyList())).thenAnswer(invocation -> success(invocation.getArgument(0)));
        List<ContestScoreboardStreamEvent> events = List.of(event(5L, 105L), event(6L, 106L));

        processor.process(events);
        processor.process(events);

        assertThat(registry.get("contest.scoreboard.applied").counter().count()).isEqualTo(2.0);
    }

    private static List<ContestScoreboardApplier.ApplyResult> success(
            List<ContestScoreboardApplier.ApplyRequest> requests) {
        return requests.stream()
                .map(request -> ContestScoreboardApplier.ApplyResult.success(
                        request.correlationId(), request.streamOffset()))
                .toList();
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

    private static ContestScoreboardStreamConsumerProperties properties() {
        return new ContestScoreboardStreamConsumerProperties(
                500, 500, Duration.ofMillis(50), Duration.ofNanos(1), Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requestsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
