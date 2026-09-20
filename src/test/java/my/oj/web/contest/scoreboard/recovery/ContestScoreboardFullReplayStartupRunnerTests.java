package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardFullReplayStartupRunnerTests {

    @Mock
    private ContestScoreboardFullReplayService fullReplayService;

    @Test
    void replaysEveryContestWhenStartupReplayIsEnabled() {
        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate())
                .run(null);

        verify(fullReplayService).replayAllContests();
    }

    @Test
    void leavesTheRestoredScoreboardAloneWhenStartupReplayIsDisabled() {
        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(false), gate())
                .run(null);

        verify(fullReplayService, never()).replayAllContests();
    }

    /**
     * The startup replay is not the only caller of the service: the retention-gap fallback replays
     * through it too, from a stream delivery. A fallback pass that is already running must leave this
     * one a no-op rather than let two readers of the same stored results run over each other.
     */
    @Test
    void aStartupReplayIsSkippedWhileAnotherReplayPassHoldsTheGate() {
        ContestScoreboardRecoveryPassGate gate = gate();

        gate.tryRun(ContestScoreboardRecoveryStrategy.PassKind.MYSQL_REPLAY, () -> {
            new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate)
                    .run(null);
            return Boolean.TRUE;
        });

        verify(fullReplayService, never()).replayAllContests();
        assertThat(gate.held()).isFalse();
    }

    /** The gate is released on the way out, so one failed replay does not close recovery for good. */
    @Test
    void theGateIsReleasedWhenAReplayFails() {
        ContestScoreboardRecoveryPassGate gate = gate();
        when(fullReplayService.replayAllContests())
                .thenThrow(new IllegalStateException("MySQL unavailable"));

        assertThatThrownBy(() ->
                        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate)
                                .run(null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(gate.held()).isFalse();
    }

    private static ContestScoreboardRecoveryPassGate gate() {
        return new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry());
    }

    private static ContestScoreboardRecoveryProperties properties(boolean startupReplayEnabled) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.FULL_REPLAY,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, startupReplayEnabled),
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
