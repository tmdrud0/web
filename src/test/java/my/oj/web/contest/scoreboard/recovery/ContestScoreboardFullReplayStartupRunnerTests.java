package my.oj.web.contest.scoreboard.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardFullReplayStartupRunnerTests {

    @Mock
    private ContestScoreboardFullReplayService fullReplayService;

    @Test
    void replaysEveryContestWhenStartupReplayIsEnabled() {
        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(true))
                .run(null);

        verify(fullReplayService).replayAllContests();
    }

    @Test
    void leavesTheRestoredScoreboardAloneWhenStartupReplayIsDisabled() {
        new ContestScoreboardFullReplayStartupRunner(fullReplayService, properties(false))
                .run(null);

        verify(fullReplayService, never()).replayAllContests();
    }

    private static ContestScoreboardRecoveryProperties properties(boolean startupReplayEnabled) {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.FULL_REPLAY,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, startupReplayEnabled),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 1000, 500,
                        3, Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED)
        );
    }
}
