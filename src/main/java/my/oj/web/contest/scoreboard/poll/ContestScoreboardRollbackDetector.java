package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;

/**
 * Detects a Redis rollback without any Stream offset: the allocator {@code R} below the MySQL watermark
 * {@code H}.
 *
 * <ul>
 *   <li>{@code R < H}: MySQL holds sequences Redis no longer issued - Redis was restored.</li>
 *   <li>{@code R = H}: consistent.</li>
 *   <li>{@code R > H}: Redis applied results whose MySQL marker has not committed yet; not a rollback.</li>
 * </ul>
 *
 * <p>On {@code R < H}, under the apply lock: the range {@code (R, H]} is persisted first and the allocator
 * is fenced to {@code H} second. If persisting fails nothing is fenced, so the next check sees the same
 * rollback. If the fence fails the range stays pending and the next check - or the script's own
 * expected-watermark refusal - fences again; the identical pending range is reused, not duplicated. After
 * the lock is released new results flow again with sequences above {@code H}, and the range is recovered
 * in the background.</p>
 *
 * <p>Callers name the check they are ({@code startup}, {@code periodic}, {@code poll-batch},
 * {@code script-refusal}, {@code recovery-refusal}); the name is only reported, in the live-impact
 * experiment trace, so a run can say which path found the rollback.</p>
 */
@Slf4j
public class ContestScoreboardRollbackDetector {

    private final ContestScoreboardSequencedApplier applier;
    private final ContestScoreboardSequenceLedger ledger;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardMySqlPollMetrics metrics;
    private final ContestScoreboardExperimentTrace trace;

    public ContestScoreboardRollbackDetector(ContestScoreboardSequencedApplier applier,
                                             ContestScoreboardSequenceLedger ledger,
                                             ContestScoreboardApplyLock applyLock,
                                             ContestScoreboardMySqlPollMetrics metrics) {
        this(applier, ledger, applyLock, metrics, ContestScoreboardExperimentTrace.NOOP);
    }

    public ContestScoreboardRollbackDetector(ContestScoreboardSequencedApplier applier,
                                             ContestScoreboardSequenceLedger ledger,
                                             ContestScoreboardApplyLock applyLock,
                                             ContestScoreboardMySqlPollMetrics metrics,
                                             ContestScoreboardExperimentTrace trace) {
        this.applier = applier;
        this.ledger = ledger;
        this.applyLock = applyLock;
        this.metrics = metrics;
        this.trace = trace;
    }

    public Detection check() {
        return check("unspecified");
    }

    /** @param trigger which check this is, reported in the experiment trace only */
    public Detection check(String trigger) {
        return applyLock.withLock(() -> {
            // The instant the check reads R and H, under the lock: when the rollback was seen.
            long checkedAt = trace.enabled() ? System.currentTimeMillis() : 0L;
            long allocator = applier.allocatorSequence();
            long watermark = ledger.highestDurableSequence();
            if (allocator >= watermark) {
                return new Detection(allocator, watermark, null);
            }
            ContestScoreboardRecoveryRange range = ledger.openRange(allocator, watermark);
            metrics.recordRollback();
            log.warn("Redis scoreboard rollback detected: allocator {} is below the MySQL watermark {};"
                    + " recovery range generation {} persisted", allocator, watermark, range.generation());
            long fenced = applier.fenceAllocator(watermark);
            metrics.recordFence();
            log.warn("Redis scoreboard allocator fenced to {}; new results resume above it while ({}, {}] is"
                    + " recovered in the background", fenced, allocator, watermark);
            if (trace.enabled()) {
                trace.recovery(new ContestScoreboardExperimentTrace.RecoveryRecord(
                        ContestScoreboardExperimentTrace.RecoveryEvent.ROLLBACK_DETECTED,
                        Thread.currentThread().getName(),
                        checkedAt,
                        checkedAt,
                        System.currentTimeMillis(),
                        -1,
                        "(" + allocator + ", " + watermark + "] generation " + range.generation()
                                + " fenced to " + fenced,
                        trigger));
            }
            return new Detection(allocator, watermark, range);
        });
    }

    /**
     * @param range the range persisted for a rollback, or {@code null} when there was none
     */
    public record Detection(long allocator, long watermark, ContestScoreboardRecoveryRange range) {

        public boolean rolledBack() {
            return range != null;
        }
    }
}
