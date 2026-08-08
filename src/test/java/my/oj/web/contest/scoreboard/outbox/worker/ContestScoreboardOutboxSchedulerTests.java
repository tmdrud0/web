package my.oj.web.contest.scoreboard.outbox.worker;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.observability.ContestOutboxDrainMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardOutboxSchedulerTests {

    @Mock
    private ContestScoreboardOutboxProcessor processor;

    @Mock
    private ContestScoreboardOutboxRecoveryService recoveryService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ContestScoreboardOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        ContestScoreboardOutboxProperties properties = new ContestScoreboardOutboxProperties(
                50,
                10,
                Duration.ofSeconds(30)
        );
        ContestOutboxDrainMetrics drainMetrics = new ContestOutboxDrainMetrics();
        drainMetrics.bindTo(registry);
        scheduler = new ContestScoreboardOutboxScheduler(processor, recoveryService, properties, drainMetrics);
    }

    @Test
    void pollClaimsAndProcessesOneBatch() {
        given(processor.processBatch(50, Duration.ofSeconds(30)))
                .willReturn(new ContestScoreboardOutboxProcessor.BatchProcessResult(20, 19, 1, 0));

        scheduler.pollAndProcess();

        verify(processor).processBatch(50, Duration.ofSeconds(30));
    }

    /**
     * A claim that no longer holds the lease changed no row, so the event is still in the backlog.
     * Counting claims rather than applied updates would let the drain rate outrun the work and
     * understate the estimated drain time built on it.
     */
    @Test
    void countsAppliedUpdatesRatherThanClaimedRows() {
        given(processor.processBatch(50, Duration.ofSeconds(30)))
                .willReturn(new ContestScoreboardOutboxProcessor.BatchProcessResult(20, 12, 3, 5));

        scheduler.pollAndProcess();

        assertThat(counter("contest.outbox.drained")).isEqualTo(12.0);
        assertThat(counter("contest.outbox.retries")).isEqualTo(3.0);
        assertThat(registry.get("contest.scoreboard.applied").counter().count()).isEqualTo(12.0);
    }

    private double counter(String name) {
        return registry.get(name).tag("outbox", ContestOutboxDrainMetrics.SCOREBOARD_OUTBOX).counter().count();
    }

    @Test
    void recoveryRunsOnItsOwnSlowerSchedule() {
        scheduler.recoverRedisState();

        InOrder ordered = inOrder(recoveryService);
        ordered.verify(recoveryService).requeueDuplicateSequences(10);
        ordered.verify(recoveryService).requeueLostTail(10);
    }
}
