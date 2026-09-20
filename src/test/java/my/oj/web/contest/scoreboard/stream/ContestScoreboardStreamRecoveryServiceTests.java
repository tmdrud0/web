package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.recovery.ContestScoreboardFullReplayService;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the retention-gap fallback reaches for.
 *
 * <p>The distinction this pins is the one the whole mode is about: a gap is repaired by re-sending
 * stored judgements through the ordinary apply path, which leaves the restored standings alone, and
 * not by resetting the contests and building them again. Those look equally "recovered" from the
 * outside, and only one of them keeps the scoreboard the snapshot restored.</p>
 *
 * <p>The fallback is also switchable, and {@code none} has to mean something stronger than "do not
 * replay": it must leave the gap unbridged, so the caller cannot move the checkpoint past results
 * that were never applied.</p>
 */
class ContestScoreboardStreamRecoveryServiceTests {

    private ContestScoreboardFullReplayService fullReplayService;

    @BeforeEach
    void setUp() {
        fullReplayService = mock(ContestScoreboardFullReplayService.class);
    }

    @Test
    void aRetentionGapIsRepairedByReplayingStoredResults() {
        when(fullReplayService.replayAllContests()).thenReturn(7);

        boolean bridged = service(RetentionGapFallback.FULL_REPLAY).recoverRetentionGap(5L, 10L);

        assertThat(bridged).isTrue();
        verify(fullReplayService).replayAllContests();
    }

    @Test
    void theDisabledFallbackReplaysNothingAndLeavesTheGapUnbridged() {
        boolean bridged = service(RetentionGapFallback.NONE).recoverRetentionGap(5L, 10L);

        assertThat(bridged).isFalse();
        verifyNoInteractions(fullReplayService);
    }

    private ContestScoreboardStreamRecoveryService service(RetentionGapFallback fallback) {
        return new ContestScoreboardStreamRecoveryService(fullReplayService, properties(fallback));
    }

    private static ContestScoreboardRecoveryProperties properties(RetentionGapFallback fallback) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.STREAM_OFFSET,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 500, 3,
                        Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        fallback,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED
                )
        );
    }
}
