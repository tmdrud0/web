package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;

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
 */
@Slf4j
public class ContestScoreboardRollbackDetector {

    private final ContestScoreboardSequencedApplier applier;
    private final ContestScoreboardSequenceLedger ledger;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardMySqlPollMetrics metrics;

    public ContestScoreboardRollbackDetector(ContestScoreboardSequencedApplier applier,
                                             ContestScoreboardSequenceLedger ledger,
                                             ContestScoreboardApplyLock applyLock,
                                             ContestScoreboardMySqlPollMetrics metrics) {
        this.applier = applier;
        this.ledger = ledger;
        this.applyLock = applyLock;
        this.metrics = metrics;
    }

    public Detection check() {
        return applyLock.withLock(() -> {
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
