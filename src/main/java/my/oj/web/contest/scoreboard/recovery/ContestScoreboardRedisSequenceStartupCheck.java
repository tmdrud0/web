package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Runs the {@code redis-seq} check once as the JVM starts.
 *
 * <p>A rollback is discovered by this mode's check and by nothing else, and the periodic triggers
 * wait one period before their first run. Without this runner a JVM that came up on a restored
 * snapshot would leave the scoreboard short by a tail for one whole period, so the mode offers the
 * first check here and lets {@code startup-check-enabled} turn it off.</p>
 *
 * <p>It goes through the scheduler rather than the service so that it shares the scheduler's guard:
 * a periodic pass that has already started must not be joined by a second reader of the allocator.
 * That coupling is also why this runner carries the owner condition separately rather than relying on
 * the scheduler's: it is registered as an {@code ApplicationRunner} in its own right, so a JVM that
 * somehow had one without the other would still check at startup. The two conditions are the same
 * condition, so in practice both are present or neither is.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "redis-seq"
)
@Conditional(ContestScoreboardRecoveryOwnerCondition.class)
@RequiredArgsConstructor
@Slf4j
class ContestScoreboardRedisSequenceStartupCheck implements ApplicationRunner {

    private final ContestScoreboardRedisSequenceScheduler scheduler;
    private final ContestScoreboardRecoveryProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.redisSeq().startupCheckEnabled()) {
            log.info("Contest scoreboard sequence check at startup is disabled; the restored scoreboard "
                    + "keeps whatever tail it lost until the next periodic check");
            return;
        }
        scheduler.runCheck("startup");
    }
}
