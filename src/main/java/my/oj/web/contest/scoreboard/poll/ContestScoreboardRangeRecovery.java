package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-applies the results of each pending {@code (from, through]} range, and nothing else.
 *
 * <p>Each iteration reads the <em>first</em> page of judged results whose sequence is inside the range,
 * ordered by sequence then submission id, and applies it with the range's upper bound as the re-sequence
 * floor. Every applied row is issued a sequence above the fenced allocator - and therefore above the range
 * - so it leaves the range when its marker commits. Re-reading the first page is what keeps a keyset from
 * skipping rows whose key changed underneath it. An empty page completes the generation, compared against
 * its generation and pending status so a newer generation is never completed by an older pass.</p>
 *
 * <p>The apply lock is held per chunk, never across a range, so the poller keeps delivering new results
 * while a lost tail is recovered. A pass that spends {@code recovery-max-iterations} leaves the range
 * pending for the next pass; a rollback during recovery stops the pass so the detector can persist the
 * new range first.</p>
 */
@Slf4j
public class ContestScoreboardRangeRecovery {

    private final ContestScoreboardSequenceLedger ledger;
    private final ContestScoreboardSequencedApplication application;
    private final ContestScoreboardRollbackDetector detector;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardMySqlPollMetrics metrics;
    private final int chunkSize;
    private final int maxIterations;
    private final Map<Long, Long> firstSeenNanos = new ConcurrentHashMap<>();

    public ContestScoreboardRangeRecovery(ContestScoreboardSequenceLedger ledger,
                                          ContestScoreboardSequencedApplication application,
                                          ContestScoreboardRollbackDetector detector,
                                          ContestScoreboardApplyLock applyLock,
                                          ContestScoreboardMySqlPollMetrics metrics,
                                          int chunkSize,
                                          int maxIterations) {
        this.ledger = ledger;
        this.application = application;
        this.detector = detector;
        this.applyLock = applyLock;
        this.metrics = metrics;
        this.chunkSize = chunkSize;
        this.maxIterations = maxIterations;
    }

    /** Works through the pending ranges oldest first; returns how many results were re-applied. */
    public int recoverPending() {
        List<ContestScoreboardRecoveryRange> pending = ledger.pendingRanges();
        metrics.recordPendingRanges(pending.size());
        int recovered = 0;
        for (ContestScoreboardRecoveryRange range : pending) {
            firstSeenNanos.putIfAbsent(range.generation(), System.nanoTime());
            RangeResult result = recover(range);
            recovered += result.applied();
            if (result.outcome() != Outcome.COMPLETED) {
                break;
            }
        }
        return recovered;
    }

    RangeResult recover(ContestScoreboardRecoveryRange range) {
        int applied = 0;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            List<ContestScoreboardSequencedResult> rows =
                    ledger.judgedResultsInRange(range.fromExclusive(), range.throughInclusive(), chunkSize);
            if (rows.isEmpty()) {
                if (ledger.completeRange(range.generation())) {
                    Long startedAt = firstSeenNanos.remove(range.generation());
                    metrics.recordRangeCompleted(startedAt == null
                            ? null : Duration.ofNanos(System.nanoTime() - startedAt));
                    log.info("Recovered scoreboard sequence range ({}, {}] generation {}: {} result(s) re-applied"
                                    + " in this pass", range.fromExclusive(), range.throughInclusive(),
                            range.generation(), applied);
                }
                return new RangeResult(Outcome.COMPLETED, applied);
            }
            ContestScoreboardSequencedApplication.ChunkOutcome outcome = applyLock.withLock(
                    () -> application.applyChunk(rows, range.throughInclusive()));
            applied += outcome.applied();
            metrics.recordRecovered(outcome.applied());
            if (outcome.rolledBack()) {
                detector.check();
                return new RangeResult(Outcome.INTERRUPTED, applied);
            }
        }
        metrics.recordUnresolved();
        log.error("Scoreboard sequence range ({}, {}] generation {} still has results after {} chunk(s); it stays"
                        + " pending and the next recovery pass continues it", range.fromExclusive(),
                range.throughInclusive(), range.generation(), maxIterations);
        return new RangeResult(Outcome.UNRESOLVED, applied);
    }

    enum Outcome { COMPLETED, INTERRUPTED, UNRESOLVED }

    record RangeResult(Outcome outcome, int applied) {
    }
}
