package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the {@code redis-seq} check on the configured periods.
 *
 * <h2>Why the periods come from the properties record rather than from a placeholder</h2>
 *
 * <p>{@code @Scheduled(fixedDelayString = "${...}")} would put the interval in two places at once:
 * the record, which the startup report prints as the effective value, and the annotation's own
 * fallback, which the scheduler actually obeys when the property is absent. The two would drift the
 * moment a default changed, and the drift would be invisible - the report would name one cadence
 * while the JVM ran another. Registering the tasks here means the record is the only definition.</p>
 *
 * <h2>Why two triggers run one check</h2>
 *
 * <p>Both periods request the same pass. The two detections share a round because they share the one
 * thing that decides their correctness - the allocator read that has to come after every database
 * read - so running them apart would read the allocator twice per period and double the database
 * work for the same answer. The effective cadence is therefore the shorter of the two, and either
 * knob alone is enough to make the check run more or less often.</p>
 *
 * <h2>Why the pass is guarded</h2>
 *
 * <p>Two passes reading the allocator at overlapping moments would each judge the other's in-flight
 * results as lost, and the two triggers are independent schedules. A pass that is already running
 * makes the later trigger a no-op rather than a second reader.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "redis-seq"
)
@RequiredArgsConstructor
@Slf4j
class ContestScoreboardRedisSequenceScheduler implements SchedulingConfigurer {

    private final ContestScoreboardRedisSequenceRecoveryService recoveryService;
    private final ContestScoreboardRedisSequenceMetrics metrics;
    private final ContestScoreboardRecoveryProperties properties;

    private final AtomicBoolean running = new AtomicBoolean();

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ContestScoreboardRecoveryProperties.RedisSequence config = properties.redisSeq();
        // The first run waits one period: without that the check would fire during startup, ahead of
        // the startup check that is the one place allowed to decide whether it runs at all.
        taskRegistrar.addFixedDelayTask(new IntervalTask(
                () -> runCheck("duplicate-check"), config.duplicateCheckInterval(), config.duplicateCheckInterval()));
        taskRegistrar.addFixedDelayTask(new IntervalTask(
                () -> runCheck("lost-tail-check"), config.lostTailCheckInterval(), config.lostTailCheckInterval()));
    }

    /**
     * One check pass, or nothing at all when a pass is already running.
     *
     * <p>Exposed because the startup check is a third trigger: it runs the same pass through the same
     * guard, so a JVM starting up cannot end up with two readers.</p>
     */
    void runCheck(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("A contest scoreboard sequence check is already running; skipping the {} trigger",
                    trigger);
            return;
        }
        try {
            ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report =
                    recoveryService.check();
            report(trigger, report);
        } catch (RuntimeException failure) {
            // Caught rather than propagated: a fixed-delay task that throws is cancelled, which would
            // leave the mode silently not checking anything - the exact failure this mode exists to
            // make visible.
            metrics.recordFailedRound();
            log.error("Contest scoreboard sequence check ({}) failed", trigger, failure);
        } finally {
            running.set(false);
        }
    }

    private void report(String trigger,
                        ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report) {
        if (report.duplicateGroups() == 0 && report.replayed() == 0) {
            log.debug("Contest scoreboard sequence check ({}) found nothing to repair after {} round(s)",
                    trigger, report.rounds());
            return;
        }
        log.warn("Contest scoreboard sequence check ({}) replayed {} result(s) in {} round(s); "
                        + "{} sequence(s) were reused; window budget exhausted: {}",
                trigger, report.replayed(), report.rounds(), report.duplicateGroups(), report.saturated());
        if (report.unresolved()) {
            log.error("Contest scoreboard sequence check ({}) spent every configured round and still found "
                            + "results to replay. Sequences held by results the scoreboard already applied "
                            + "cannot be reissued by replaying them; the standings need a full replay or a "
                            + "reset-based rebuild to be renumbered.",
                    trigger);
        }
    }
}
