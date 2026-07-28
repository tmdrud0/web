package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import my.oj.web.contest.scoreboard.outbox.SequencedOutboxRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardOutboxRecoveryServiceTests {

    @Mock
    private ContestScoreboardOutboxRepository outboxRepository;

    @Mock
    private ContestScoreboardApplier outboxApplier;

    private ContestScoreboardOutboxRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new ContestScoreboardOutboxRecoveryService(outboxRepository, outboxApplier);
    }

    @Test
    void requeueDuplicateSequences_resetsEveryRowInDuplicateGroups() {
        given(outboxRepository.findDuplicateRedisSequences(any(Pageable.class)))
                .willReturn(List.of(41L, 42L));
        given(outboxRepository.requeueByRedisSequenceIn(List.of(41L, 42L))).willReturn(4);

        int requeued = recoveryService.requeueDuplicateSequences(10);

        assertThat(requeued).isEqualTo(4);
        verify(outboxRepository).requeueByRedisSequenceIn(List.of(41L, 42L));
    }

    @Test
    void requeueDuplicateSequences_doesNothingWhenNoCollisionExists() {
        given(outboxRepository.findDuplicateRedisSequences(any(Pageable.class))).willReturn(List.of());

        int requeued = recoveryService.requeueDuplicateSequences(10);

        assertThat(requeued).isZero();
        verify(outboxRepository, never()).requeueByRedisSequenceIn(any());
    }

    @Test
    void requeueLostTail_resetsOnlyTheRowsBeyondTheRedisAllocator() {
        given(outboxRepository.findHighestRedisSequences(any(Pageable.class)))
                .willReturn(List.of(
                        new SequencedOutboxRow(22L, 72L),
                        new SequencedOutboxRow(21L, 71L),
                        new SequencedOutboxRow(20L, 70L)
                ));
        given(outboxApplier.currentSequence()).willReturn(70L);
        given(outboxRepository.requeueByIdIn(List.of(22L, 21L))).willReturn(2);

        int requeued = recoveryService.requeueLostTail(50);

        assertThat(requeued).isEqualTo(2);
        verify(outboxRepository).requeueByIdIn(List.of(22L, 21L));
    }

    /**
     * A sequence reaches the outbox only after the allocator handed it out, so reading the
     * allocator after the candidates means concurrent workers can only raise the bar. Reading
     * it first would leave a stale bar and requeue everything they completed in between.
     */
    @Test
    void requeueLostTail_readsTheAllocatorAfterTheCandidatesSoConcurrentProgressIsNotMistakenForLoss() {
        given(outboxRepository.findHighestRedisSequences(any(Pageable.class)))
                .willReturn(List.of(
                        new SequencedOutboxRow(22L, 72L),
                        new SequencedOutboxRow(21L, 71L)
                ));
        given(outboxApplier.currentSequence()).willReturn(80L);

        int requeued = recoveryService.requeueLostTail(50);

        assertThat(requeued).isZero();
        verify(outboxRepository, never()).requeueByIdIn(any());

        InOrder order = inOrder(outboxRepository, outboxApplier);
        order.verify(outboxRepository).findHighestRedisSequences(any(Pageable.class));
        order.verify(outboxApplier).currentSequence();
    }

    @Test
    void requeueLostTail_doesNotReadTheAllocatorWhenNothingHasBeenSequencedYet() {
        given(outboxRepository.findHighestRedisSequences(any(Pageable.class))).willReturn(List.of());

        int requeued = recoveryService.requeueLostTail(50);

        assertThat(requeued).isZero();
        verify(outboxApplier, never()).currentSequence();
        verify(outboxRepository, never()).requeueByIdIn(any());
    }
}
