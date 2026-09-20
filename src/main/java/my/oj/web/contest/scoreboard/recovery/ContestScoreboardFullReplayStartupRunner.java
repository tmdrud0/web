package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Runs the {@code full-replay} mode's replay once, as the JVM starts.
 *
 * <p>Re-sending everything is the whole of what this mode does, so starting it is what selecting
 * the mode means. It runs to completion before the application is reported ready on purpose: an
 * operator who chose this mode is waiting for the scoreboard to be complete, and the apply lock
 * keeps the live stream path correct while it runs.</p>
 *
 * <p>Two conditions select this bean and both are needed. The mode condition says which mode the
 * JVM is running; {@link ContestScoreboardRecoveryOwnerCondition} says whether this JVM is the one
 * that runs recovery. Without the second, every role configured {@code mode=full-replay} would
 * replay - and a web or judge role that was pointed at that mode for its reporting would run a full
 * replay on startup while the batch role ran the one the deployment intended.</p>
 *
 * <p>Only this runner is conditional on the mode. The service itself stays available in every mode,
 * because the retention-gap fallback also replays through it.</p>
 *
 * <p>The replay runs through {@link ContestScoreboardRecoveryPassGate} because it is not the only
 * caller of the service: the retention-gap fallback replays through it as well, from a stream
 * delivery, and could in principle arrive while startup is still replaying. That is the same
 * collision the gate exists for - two readers of the same stored results, each judging the other's
 * in-flight writes. It is a JVM-local gate; two instances are still two replays.</p>
 *
 * <p>This runner is also what releases the held stream consumer. In this mode the consumer does not
 * begin reading until the replay has run, because a consumer that started first would re-read the
 * restored history from the stream and this mode's basis would then be replaying MySQL over results
 * that had already been put back - see {@link ContestScoreboardRecoveryStrategy
 * #recoversHistoryBeforeConsuming()}. The boundary is reported only after the replay returns, and its
 * absence is the one way consumption can fail to begin: a replay that threw fails the application
 * rather than leaving a consumer held behind it, and a gate held by another pass is logged at ERROR
 * naming what stayed held.</p>
 *
 * <p>A release that ran but could not start the consumer is not swallowed either, and this is the
 * reason: the boundary is what an operator is waiting on, and a JVM that came up with nothing consuming
 * while reporting its history recovered would be the quiet failure this whole boundary exists to
 * prevent. The exception is thrown out of this runner, so the application fails to start loudly.</p>
 *
 * <p>Keeping the failed action waiting on the boundary (see {@link ContestScoreboardRecoveryCutover})
 * does not buy a retry inside this JVM, and it is not relied on here: in this mode <em>this runner is
 * the only thing that reports the boundary</em>. The retention-gap fallback replays through the same
 * service but reports nothing, and there is no periodic report as there is in {@code redis-seq}. The
 * retry is the restart, which runs this pass again - so the failure has to reach the caller rather than
 * be left to a later report that will not come.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "full-replay"
)
@Conditional(ContestScoreboardRecoveryOwnerCondition.class)
@RequiredArgsConstructor
@Slf4j
class ContestScoreboardFullReplayStartupRunner implements ApplicationRunner {

    private final ContestScoreboardFullReplayService fullReplayService;
    private final ContestScoreboardRecoveryProperties properties;
    private final ContestScoreboardRecoveryPassGate gate;
    private final ContestScoreboardRecoveryCutover cutover;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.fullReplay().startupReplayEnabled()) {
            // Refused rather than merely announced when the consumer is on - see
            // ContestScoreboardRecoveryValidator - so reaching here means nothing is waiting on the
            // boundary this pass would have reported.
            log.info("Contest scoreboard full replay at startup is disabled; the restored scoreboard "
                    + "stays as it is until a retention gap or an operator triggers a replay");
            return;
        }
        gate.tryRun(PassKind.MYSQL_REPLAY, () -> fullReplayService.replayAllContests())
                .ifPresentOrElse(replayed -> {
                    log.info("Contest scoreboard full replay re-sent {} stored result(s) from MySQL",
                            replayed);
                    cutover.markCovered("the full-replay startup replay");
                }, () -> log.error("Another recovery pass held the gate while this mode's startup replay "
                        + "was requested, so the replay has not run and the stream consumer stays held "
                        + "until one does. Nothing else on this instance runs a replay at startup, so "
                        + "this means a pass was already under way - the retention-gap fallback is the "
                        + "only other caller"));
    }
}
