package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;
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
    private final ContestScoreboardRecoveryCutover cutover = new ContestScoreboardRecoveryCutover();

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

    /**
     * A check that covered the scoreboard history releases the held stream consumer, which is what lets
     * live consumption begin in this mode. A check that failed does not: nothing was repaired, and
     * releasing the consumer would hand the restored history to the stream - the basis of the mode this
     * instance is not running. The next period retries the pass and releases it then, which is why this
     * is reported from every trigger rather than from the startup check alone.
     */
    @Test
    void onlyACheckThatCoveredTheHistoryReleasesTheHeldConsumer() {
        List<String> released = new ArrayList<>();
        cutover.whenCovered(() -> released.add("consumer"));
        when(recoveryService.check())
                .thenThrow(new IllegalStateException("Redis unavailable"))
                .thenReturn(new SequenceCheckReport(1, 0, 0, false, false));

        scheduler().runCheck("startup");
        assertThat(cutover.isCovered()).isFalse();
        assertThat(released).isEmpty();

        // The next period retries the same pass, and it is a covering pass that releases the consumer.
        scheduler().runCheck("duplicate-check");

        assertThat(released).containsExactly("consumer");
        assertThat(cutover.isCovered()).isTrue();
    }

    /**
     * A check that returned without covering the history may not claim the boundary, for the same reason
     * a failed one may not: the history it did not account for is exactly what the stream would put back
     * instead of the mode's own basis. {@code saturated} - the window budget ran out before the tail was
     * walked to its end - is the retryable shape of that, so the next period asks again and releases the
     * consumer when a pass does cover the history.
     */
    @Test
    void aCheckThatDidNotCoverTheHistoryDoesNotReleaseTheHeldConsumer() {
        List<String> released = new ArrayList<>();
        cutover.whenCovered(() -> released.add("consumer"));
        when(recoveryService.check())
                .thenReturn(new SequenceCheckReport(2, 3, 1, true, false))
                .thenReturn(new SequenceCheckReport(1, 0, 0, false, false));

        scheduler().runCheck("startup");

        assertThat(cutover.isCovered()).isFalse();
        assertThat(released).isEmpty();

        scheduler().runCheck("duplicate-check");

        assertThat(released).containsExactly("consumer");
    }

    /**
     * The same for a pass that spent every configured round with results still to replay. This mode
     * cannot repair that by asking again - it is the outcome the strategy calls unrecoverable - so the
     * consumer stays held and the operator is told; what must not happen is the stream quietly putting the
     * history back while the mode reports itself as the thing that recovered it.
     */
    @Test
    void aPassThatRanOutOfRoundsDoesNotReleaseTheHeldConsumer() {
        cutover.whenCovered(() -> fail("the consumer must not be released on an unresolved pass"));
        when(recoveryService.check()).thenReturn(new SequenceCheckReport(3, 4, 2, false, true));

        scheduler().runCheck("startup");
        scheduler().runCheck("lost-tail-check");

        assertThat(cutover.isCovered()).isFalse();
    }

    /**
     * A trigger turned away by the gate has not run the check either, so it may not claim the boundary:
     * the pass that holds the gate is the one that will report it.
     */
    @Test
    void aTriggerThatWasSkippedDoesNotReleaseTheHeldConsumer() {
        gate.tryRun(ContestScoreboardRecoveryStrategy.PassKind.SEQUENCE_CHECK, () -> {
            scheduler().runCheck("startup");
            return Boolean.TRUE;
        });

        assertThat(cutover.isCovered()).isFalse();
    }

    private ContestScoreboardRedisSequenceScheduler scheduler() {
        return new ContestScoreboardRedisSequenceScheduler(
                recoveryService,
                metrics,
                properties(),
                gate,
                cutover
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
