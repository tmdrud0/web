package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sequence check's periodic triggers, and the guard they share.
 *
 * <p>The guard is the point. This scheduler is not the only thing that runs a sequence check - the
 * live path asks for the same pass when a delivery arrives above a checkpoint the stream no longer
 * retains - so a flag owned by this class would let two allocator readers run at once, which is the
 * one thing a sequence check cannot survive: each would judge the other's in-flight results as lost
 * and replay them.</p>
 *
 * <p>A failing check must also stay inside the scheduled task. A fixed-delay task that throws is
 * cancelled, which would leave the mode silently checking nothing at all.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardRedisSequenceSchedulerTests {

    @Mock
    private ContestScoreboardRedisSequenceRecoveryService recoveryService;
    @Mock
    private ContestScoreboardRedisSequenceMetrics metrics;

    private final ContestScoreboardRecoveryPassGate gate = new ContestScoreboardRecoveryPassGate(
            new SimpleMeterRegistry());

    @Test
    void runsTheCheckOnATrigger() {
        when(recoveryService.check()).thenReturn(new SequenceCheckReport(1, 0, 0, false, false));

        scheduler().runCheck("duplicate-check");

        verify(recoveryService).check();
    }

    @Test
    void skipsTheTriggerWhileAnotherRecoveryPassHoldsTheGate() {
        gate.tryRun(ContestScoreboardRecoveryStrategy.PassKind.MYSQL_REPLAY, () -> {
            scheduler().runCheck("duplicate-check");
            return Boolean.TRUE;
        });

        verify(recoveryService, never()).check();
        assertThatCode(() -> gate.tryRun(ContestScoreboardRecoveryStrategy.PassKind.SEQUENCE_CHECK, () -> 1))
                .doesNotThrowAnyException();
    }

    @Test
    void aFailingCheckIsRecordedRatherThanThrownOutOfTheScheduledTask() {
        when(recoveryService.check()).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatCode(() -> scheduler().runCheck("lost-tail-check")).doesNotThrowAnyException();

        verify(metrics).recordFailedRound();
        // The gate is given back, so the next period is not skipped for good.
        assertThatCode(() -> scheduler().runCheck("lost-tail-check")).doesNotThrowAnyException();
    }

    private ContestScoreboardRedisSequenceScheduler scheduler() {
        return new ContestScoreboardRedisSequenceScheduler(
                recoveryService,
                metrics,
                properties(),
                gate
        );
    }

    private static ContestScoreboardRecoveryProperties properties() {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.REDIS_SEQ,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 500,
                        3, Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED),
                new ContestScoreboardRecoveryProperties.RecoveryOwner(true)
        );
    }
}
