package my.oj.web.contest.scoreboard.outbox.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardOutboxSchedulerTests {

    @Mock
    private ContestScoreboardOutboxProcessor processor;

    @Mock
    private ContestScoreboardOutboxRecoveryService recoveryService;

    @Mock
    private ContestScoreboardOutboxProcessLock processLock;

    private ContestScoreboardOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        ContestScoreboardOutboxProperties properties = new ContestScoreboardOutboxProperties(
                50,
                10,
                Duration.ofSeconds(30)
        );
        scheduler = new ContestScoreboardOutboxScheduler(processor, recoveryService, processLock, properties);
    }

    @Test
    void pollClaimsAndProcessesOneBatch() {
        given(processLock.executeIfAcquired(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> Optional.of(
                        ((Supplier<?>) invocation.getArgument(0)).get()
                ));
        given(processor.processBatch(50, Duration.ofSeconds(30)))
                .willReturn(new ContestScoreboardOutboxProcessor.BatchProcessResult(20, 19, 1, 0));

        scheduler.pollAndProcess();

        verify(processor).processBatch(50, Duration.ofSeconds(30));
    }

    @Test
    void pollDoesNothingWhenAnotherInstanceOwnsTheProcessLock() {
        given(processLock.executeIfAcquired(org.mockito.ArgumentMatchers.any()))
                .willReturn(Optional.empty());

        scheduler.pollAndProcess();

        org.mockito.Mockito.verifyNoInteractions(processor);
    }

    @Test
    void recoveryRunsOnItsOwnSlowerSchedule() {
        scheduler.recoverRedisState();

        InOrder ordered = inOrder(recoveryService);
        ordered.verify(recoveryService).requeueDuplicateSequences(10);
        ordered.verify(recoveryService).requeueLostTail(10);
    }
}
