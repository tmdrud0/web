package my.oj.web.contest.scoreboard.recovery;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts at most one immediate sequence check without making a live stream batch wait for it.
 *
 * <p>The periodic scheduler remains the retry owner. This component contributes one prompt attempt
 * when a live rollback is observed, records the rollback obligation before competing for the shared
 * recovery-pass gate, and never queues a second attempt behind one already submitted by this
 * component. If the prompt attempt loses the gate, the obligation remains for the next periodic
 * pass, including the judged rows caught between Redis apply and MySQL sequence marking.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "redis-seq"
)
@Conditional(ContestScoreboardRecoveryOwnerCondition.class)
@Slf4j
class ContestScoreboardRedisSequenceLiveRecovery {

    private final ContestScoreboardRedisSequenceRecoveryService recoveryService;
    private final ContestScoreboardRedisSequenceMetrics metrics;
    private final ContestScoreboardRecoveryPassGate gate;
    private final ExecutorService executor;
    private final AtomicBoolean submitted = new AtomicBoolean();

    @Autowired
    ContestScoreboardRedisSequenceLiveRecovery(
            ContestScoreboardRedisSequenceRecoveryService recoveryService,
            ContestScoreboardRedisSequenceMetrics metrics,
            ContestScoreboardRecoveryPassGate gate
    ) {
        this(recoveryService, metrics, gate, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scoreboard-redis-seq-live-recovery");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ContestScoreboardRedisSequenceLiveRecovery(
            ContestScoreboardRedisSequenceRecoveryService recoveryService,
            ContestScoreboardRedisSequenceMetrics metrics,
            ContestScoreboardRecoveryPassGate gate,
            ExecutorService executor
    ) {
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.gate = gate;
        this.executor = executor;
    }

    /**
     * Requests one prompt check. A false result means one is already submitted or shutdown refused it;
     * in both cases the fixed-delay checks remain the retry path.
     */
    boolean trigger() {
        // Every observed rollback is a correctness obligation, even when the prompt worker is already
        // submitted for an earlier one. A duplicate request costs one bounded scan; dropping a later
        // request could leave its apply-before-marker window unrepaired.
        recoveryService.requestRollbackRepair();
        if (!submitted.compareAndSet(false, true)) {
            log.debug("An immediate redis-seq live-recovery check is already submitted");
            return false;
        }
        try {
            executor.execute(this::run);
            return true;
        } catch (RejectedExecutionException failure) {
            submitted.set(false);
            metrics.recordFailedRound();
            log.error("The immediate redis-seq live-recovery check could not be submitted; the periodic "
                    + "checks remain the retry path", failure);
            return false;
        }
    }

    private void run() {
        try {
            Optional<Boolean> ran = gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
                try {
                    ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport report =
                            recoveryService.check();
                    if (!report.coveredTheWholeSet()) {
                        log.warn("The immediate redis-seq live-recovery check did not cover the whole set "
                                        + "(saturated={}, unresolved={}); periodic checks will continue",
                                report.saturated(), report.unresolved());
                    }
                    return report.coveredTheWholeSet();
                } catch (RuntimeException failure) {
                    metrics.recordFailedRound();
                    log.error("The immediate redis-seq live-recovery check failed; the periodic checks "
                            + "remain the retry path", failure);
                    return Boolean.FALSE;
                }
            });
            if (ran.isEmpty()) {
                log.warn("The immediate redis-seq live-recovery check was skipped because another recovery "
                        + "pass held the gate; the periodic checks remain the retry path");
            }
        } finally {
            submitted.set(false);
        }
    }

    boolean submitted() {
        return submitted.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
