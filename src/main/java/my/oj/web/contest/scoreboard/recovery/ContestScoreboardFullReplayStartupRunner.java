package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the {@code full-replay} mode's replay once, as the JVM starts.
 *
 * <p>Re-sending everything is the whole of what this mode does, so starting it is what selecting
 * the mode means. It runs to completion before the application is reported ready on purpose: an
 * operator who chose this mode is waiting for the scoreboard to be complete, and the apply lock
 * keeps the live stream path correct while it runs.</p>
 *
 * <p>Only this runner is conditional on the mode. The service itself stays available in every mode,
 * because the retention-gap fallback also replays through it.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "full-replay"
)
@RequiredArgsConstructor
@Slf4j
class ContestScoreboardFullReplayStartupRunner implements ApplicationRunner {

    private final ContestScoreboardFullReplayService fullReplayService;
    private final ContestScoreboardRecoveryProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.fullReplay().startupReplayEnabled()) {
            log.info("Contest scoreboard full replay at startup is disabled; the restored scoreboard "
                    + "stays as it is until a retention gap or an operator triggers a replay");
            return;
        }
        int replayed = fullReplayService.replayAllContests();
        log.info("Contest scoreboard full replay re-sent {} stored result(s) from MySQL", replayed);
    }
}
