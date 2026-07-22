package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardOutboxProcessorTests {

    @Mock
    private ContestScoreboardOutboxService outboxService;

    @Mock
    private ContestScoreboardOutboxApplier outboxApplier;

    @Mock
    private ContestScoreboardOutboxStore outboxStore;

    private ContestScoreboardOutboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ContestScoreboardOutboxProcessor(outboxService, outboxApplier, outboxStore);
    }

    @Test
    void processById_assignsSequenceReturnedByAtomicRedisApply() {
        ContestScoreboardOutbox outbox = pendingOutbox(1L, null);
        given(outboxService.lockById(1L)).willReturn(outbox);
        given(outboxApplier.apply(1L, outbox.toPayload())).willReturn(55L);

        boolean processed = processor.processById(1L);

        assertThat(processed).isTrue();
        assertThat(outbox.getRedisSequence()).isEqualTo(55L);
        assertThat(outbox.getStatus()).isEqualTo(ContestScoreboardOutboxStatus.COMPLETED);
        assertThat(outbox.getProcessedAt()).isNotNull();

        ArgumentCaptor<ContestScoreboardOutboxPayload> payloadCaptor =
                ArgumentCaptor.forClass(ContestScoreboardOutboxPayload.class);
        verify(outboxApplier).apply(org.mockito.ArgumentMatchers.eq(1L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().contestSubmissionId()).isEqualTo(100L);
        assertThat(payloadCaptor.getValue().result()).isEqualTo(SubmissionResult.ACCEPTED);
    }

    @Test
    void processById_allowsDirectStoreWithoutRedisSequence() {
        ContestScoreboardOutbox outbox = pendingOutbox(2L, null);
        given(outboxService.lockById(2L)).willReturn(outbox);
        given(outboxApplier.apply(2L, outbox.toPayload())).willReturn(null);

        boolean processed = processor.processById(2L);

        assertThat(processed).isTrue();
        assertThat(outbox.getRedisSequence()).isNull();
        assertThat(outbox.getStatus()).isEqualTo(ContestScoreboardOutboxStatus.COMPLETED);
    }

    @Test
    void processById_marksFailedWhenAtomicApplyThrows() {
        ContestScoreboardOutbox outbox = pendingOutbox(3L, null);
        given(outboxService.lockById(3L)).willReturn(outbox);
        given(outboxApplier.apply(3L, outbox.toPayload()))
                .willThrow(new IllegalStateException("redis down"));

        boolean processed = processor.processById(3L);

        assertThat(processed).isFalse();
        assertThat(outbox.getRedisSequence()).isNull();
        assertThat(outbox.getStatus()).isEqualTo(ContestScoreboardOutboxStatus.FAILED);
        assertThat(outbox.getLastErrorMessage()).isEqualTo("redis down");
    }

    @Test
    void processBatchAppliesClaimedEventsAndCompletesSuccessesAndFailuresTogether() {
        Duration lease = Duration.ofSeconds(30);
        ContestScoreboardOutboxPayload accepted = payload(1001L, SubmissionResult.ACCEPTED);
        ContestScoreboardOutboxPayload failed = payload(1002L, SubmissionResult.WRONG_ANSWER);
        ContestScoreboardOutboxStore.ClaimedEvent first =
                new ContestScoreboardOutboxStore.ClaimedEvent(11L, accepted, "claim-token");
        ContestScoreboardOutboxStore.ClaimedEvent second =
                new ContestScoreboardOutboxStore.ClaimedEvent(12L, failed, "claim-token");
        given(outboxStore.claim(50, lease)).willReturn(List.of(first, second));
        given(outboxApplier.applyAll(org.mockito.ArgumentMatchers.anyList())).willReturn(List.of(
                ContestScoreboardOutboxApplier.ApplyResult.success(11L, 101L),
                ContestScoreboardOutboxApplier.ApplyResult.failure(12L, "wrong Redis key type")
        ));
        given(outboxStore.completeAll(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList()))
                .willReturn(new ContestScoreboardOutboxStore.BatchCompletionResult(1, 1, 1, 1));

        ContestScoreboardOutboxProcessor.BatchProcessResult result = processor.processBatch(50, lease);

        assertThat(result).isEqualTo(new ContestScoreboardOutboxProcessor.BatchProcessResult(2, 1, 1, 0));
        verify(outboxStore).completeAll(
                org.mockito.ArgumentMatchers.argThat(completed ->
                        completed.size() == 1
                                && completed.get(0).event().eventId() == 11L
                                && completed.get(0).redisSequence().equals(101L)),
                org.mockito.ArgumentMatchers.argThat(failures ->
                        failures.size() == 1
                                && failures.get(0).event().eventId() == 12L
                                && failures.get(0).error().contains("wrong Redis key type"))
        );
    }

    private ContestScoreboardOutbox pendingOutbox(Long id, Long redisSequence) {
        ContestScoreboardOutbox outbox = ContestScoreboardOutbox.pending(
                100L,
                10L,
                20L,
                30L,
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                SubmissionResult.ACCEPTED,
                LocalDateTime.of(2026, 3, 10, 12, 2),
                redisSequence
        );
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }

    private ContestScoreboardOutboxPayload payload(Long submissionId, SubmissionResult result) {
        return new ContestScoreboardOutboxPayload(
                submissionId,
                10L,
                20L,
                30L,
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                result,
                LocalDateTime.of(2026, 3, 10, 12, 2)
        );
    }
}
