package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardFullReplayStartupRunnerTests {

    @Mock
    private ContestScoreboardFullReplayService fullReplayService;

    private final ContestScoreboardRecoveryCutover cutover = new ContestScoreboardRecoveryCutover();

    @Test
    void replaysEveryContestWhenStartupReplayIsEnabled() {
        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate(), cutover)
                .run(null);

        verify(fullReplayService).replayAllContests();
    }

    @Test
    void leavesTheRestoredScoreboardAloneWhenStartupReplayIsDisabled() {
        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(false), gate(), cutover)
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
            new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate, cutover)
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
                        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate, cutover)
                                .run(null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(gate.held()).isFalse();
    }

    private static ContestScoreboardRecoveryPassGate gate() {
        return new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry());
    }

    /**
     * The replay is what releases the held stream consumer, and it does so only after it has returned:
     * this mode's basis is the thing that may rebuild the restored history, and a consumer reading from
     * the stored checkpoint would otherwise have done it first.
     */
    @Test
    void theReplayReleasesTheHeldConsumerOnceItHasRun() {
        List<String> released = new ArrayList<>();
        cutover.whenCovered(() -> released.add("consumer"));

        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate(), cutover)
                .run(null);

        assertThat(released).containsExactly("consumer");
        assertThat(cutover.isCovered()).isTrue();
    }

    /**
     * Nothing is released when the replay did not run, in either of the two ways that happens.
     *
     * <p>A disabled replay is refused outright when the consumer is on - see
     * {@code ContestScoreboardRecoveryValidator} - so the configuration below only exists on a role that
     * does not consume the stream, and what this pins is that the runner does not claim the boundary on
     * behalf of a pass it did not run. A pass another replay held the gate out of is the same claim: the
     * range it would have covered is untouched, so nothing may be told that it is covered.</p>
     */
    @Test
    void nothingIsReleasedWhenTheReplayDidNotRun() {
        List<String> released = new ArrayList<>();
        cutover.whenCovered(() -> released.add("consumer"));

        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(false), gate(), cutover)
                .run(null);
        assertThat(cutover.isCovered()).isFalse();

        ContestScoreboardRecoveryPassGate gate = gate();
        gate.tryRun(ContestScoreboardRecoveryStrategy.PassKind.MYSQL_REPLAY, () -> {
            new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true), gate, cutover)
                    .run(null);
            return Boolean.TRUE;
        });

        assertThat(released).isEmpty();
        assertThat(cutover.isCovered()).isFalse();
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
