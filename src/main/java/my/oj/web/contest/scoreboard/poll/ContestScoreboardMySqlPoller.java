package my.oj.web.contest.scoreboard.poll;

import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;

import java.util.List;

/**
 * Delivers judged results from MySQL to the Redis scoreboard: every judged row with no
 * {@code scoreboard_applied_seq}, in submission-id order.
 *
 * <p>The upper submission id is fixed when a poll starts, so a busy contest cannot keep one poll chasing
 * new results; they are the next poll's. The keyset continues from the last id of each page. Rows the
 * page skips because they are still {@code PENDING} are read again once judged.</p>
 *
 * <p>The same query is the repair of the cross-store window: a row Redis applied whose MySQL marker was
 * lost still has no sequence, so it is polled again and the script hands back the sequence it holds.</p>
 *
 * <p>Each batch takes the apply lock and applies. The lock is held per batch.</p>
 */
public class ContestScoreboardMySqlPoller {

    private final ContestScoreboardSequenceLedger ledger;
    private final ContestScoreboardSequencedApplication application;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardMySqlPollMetrics metrics;
    private final int batchSize;

    public ContestScoreboardMySqlPoller(ContestScoreboardSequenceLedger ledger,
                                        ContestScoreboardSequencedApplication application,
                                        ContestScoreboardApplyLock applyLock,
                                        ContestScoreboardMySqlPollMetrics metrics,
                                        int batchSize) {
        this.ledger = ledger;
        this.application = application;
        this.applyLock = applyLock;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    /** One poll up to the highest judged unsequenced id present when it started; returns rows applied. */
    public int pollOnce() {
        Long throughId = ledger.highestUnsequencedJudgedSubmissionId();
        if (throughId == null) {
            return 0;
        }
        Long afterId = null;
        int applied = 0;
        while (true) {
            List<ContestScoreboardSequencedResult> rows =
                    ledger.unsequencedJudgedResults(afterId, throughId, batchSize);
            if (rows.isEmpty()) {
                return applied;
            }
            ContestScoreboardSequencedApplication.ChunkOutcome outcome =
                    applyLock.withLock(() -> application.applyChunk(rows, 0L));
            applied += outcome.applied();
            metrics.recordApplied(outcome.applied());
            if (outcome.rolledBack()) {
                // Redis refused: its allocator is below the watermark. What this batch applied is
                // recorded; nothing more is applied until the allocator is repaired.
                return applied;
            }
            if (rows.size() < batchSize) {
                return applied;
            }
            afterId = rows.get(rows.size() - 1).submissionId();
        }
    }
}
