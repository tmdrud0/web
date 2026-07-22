package my.oj.web.contest.scoreboard.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardOutboxRecoveryServiceTests {

    @Mock
    private ContestScoreboardOutboxRepository outboxRepository;

    @Mock
    private ContestScoreboardOutboxApplier outboxApplier;

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
    void requeueLostTail_resetsRowsBeyondRedisAllocator() {
        given(outboxApplier.currentSequence()).willReturn(70L);
        given(outboxRepository.findIdsAboveRedisSequence(eq(70L), any(Pageable.class)))
                .willReturn(List.of(21L, 22L));
        given(outboxRepository.requeueByIdIn(List.of(21L, 22L))).willReturn(2);

        int requeued = recoveryService.requeueLostTail(50);

        assertThat(requeued).isEqualTo(2);
        verify(outboxRepository).requeueByIdIn(List.of(21L, 22L));
    }
}
