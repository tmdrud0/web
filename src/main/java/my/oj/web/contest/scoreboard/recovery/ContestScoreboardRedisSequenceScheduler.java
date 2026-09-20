package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
 * results as lost, and the triggers are independent schedules. A pass that is already running
 * makes the later trigger a no-op rather than a second reader.</p>
 *
 * <p>The guard is {@link ContestScoreboardRecoveryPassGate} rather than a flag of this class's own,
 * because the scheduler is not the only thing that runs this check: the live path asks the sequence
 * strategy for the same pass when a delivery arrives above a checkpoint that was not retained, and a
 * scheduler-local flag would not see it. That gate is JVM-local, so it holds within one instance and
 * says nothing about two - see {@code ARCHITECTURE.md} §3.5.</p>
 *
 * <h2>Why the owner condition is on this class</h2>
 *
 * <p>Registering the tasks <em>is</em> running the check, so the owner gate has to be applied where
 * the tasks are registered. A scheduler that came up and skipped each run would leave two intervals
 * firing on every non-owner role, which is the duplicate reading the gate exists to prevent. Removing
 * the bean removes the intervals.</p>
 *
 * <h2>Why a completed pass reports the history-recovery boundary</h2>
 *
 * <p>In this mode the stream consumer does not begin reading until the check has run, because a
 * consumer that started first would re-read the restored history from the stream and the check would
 * then find nothing to repair - see
 * {@link ContestScoreboardRecoveryStrategy#recoversHistoryBeforeConsuming()}. The boundary is reported
 * from here so that any completed pass releases it, not just the startup one.</p>
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
class ContestScoreboardRedisSequenceScheduler implements SchedulingConfigurer {

    private final ContestScoreboardRedisSequenceRecoveryService recoveryService;
    private final ContestScoreboardRedisSequenceMetrics metrics;
    private final ContestScoreboardRecoveryProperties properties;
    private final ContestScoreboardRecoveryPassGate gate;
    private final ContestScoreboardRecoveryCutover cutover;

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
     * One check pass, or nothing at all when a pass already holds the gate.
     *
     * <p>Exposed because the startup check is a third trigger: it runs the same pass through the same
     * gate, so a JVM starting up cannot end up with two readers.</p>
     *
     * <p>A pass that covered the scoreboard history reports the history-recovery boundary, which is what
     * releases the held stream consumer in this mode. Reporting it here rather than in the startup check
     * is deliberate: the startup check is only one of the triggers, and if it fails the next period
     * retries the same pass - and should release the consumer when it succeeds, rather than leaving a JVM
     * that came up during a Redis outage consuming nothing for good.</p>
     *
     * <p>The boundary is reported on coverage and not on the pass merely returning. A check that spent
     * its window budget before the tail was walked to its end, or every configured round with results
     * still to replay, has not established that this mode's basis can account for the history - it is the
     * same distinction {@link ContestScoreboardRecoveryStrategy.Outcome} draws between {@code COVERED} and
     * the two outcomes that are not answers. Releasing the consumer on such a pass would hand the missing
     * history back to the stream, which is the one thing the hold and this mode's startup check exist to
     * prevent. So the consumer keeps waiting, the next period asks again - rounds replay what they find,
     * so the next pass has less to look at - and a pass that can never cover the history leaves the
     * instance loudly not consuming rather than quietly recovering by the wrong basis.</p>
     */
    void runCheck(String trigger) {
        Optional<Boolean> covered = gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
            try {
                ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report =
                        recoveryService.check();
                report(trigger, report);
                return report.coveredTheWholeSet();
            } catch (RuntimeException failure) {
                // Caught rather than propagated: a fixed-delay task that throws is cancelled, which
                // would leave the mode silently not checking anything - the exact failure this mode
                // exists to make visible.
                metrics.recordFailedRound();
                log.error("Contest scoreboard sequence check ({}) failed", trigger, failure);
                return Boolean.FALSE;
            }
        });
        if (covered.isEmpty()) {
            // Another pass holds the gate. Whether the boundary is reported is that pass's decision, and
            // it is the same decision this one would have made.
            log.debug("Contest scoreboard sequence check ({}) did not run: another recovery pass holds the "
                    + "gate", trigger);
            return;
        }
        if (covered.get()) {
            cutover.markCovered("the redis-seq " + trigger + " check");
            return;
        }
        log.error("Contest scoreboard sequence check ({}) did not cover the scoreboard history, so the "
                        + "stream consumer stays held: it resumes only behind a pass that does, because a "
                        + "consumer that started first would put the restored history back through the "
                        + "stream - the basis of the stream-offset mode this instance is not running. The "
                        + "next check will ask again; if the log above reports unresolved rounds, the "
                        + "standings need a full replay or a reset-based rebuild",
                trigger);
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
