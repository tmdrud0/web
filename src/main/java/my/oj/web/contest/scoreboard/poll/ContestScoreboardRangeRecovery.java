package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryRecord;

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
 *
 * <p>With the live-impact trace on, each call of {@link #recover} is one pass - {@code PASS_START}, a
 * {@code CHUNK} per applied chunk with its lock wait, {@code PASS_END} with the outcome - so the experiment
 * reads a range recovery the way it reads a Stream-mode replay.</p>
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
    private final ContestScoreboardExperimentTrace trace;

    public ContestScoreboardRangeRecovery(ContestScoreboardSequenceLedger ledger,
                                          ContestScoreboardSequencedApplication application,
                                          ContestScoreboardRollbackDetector detector,
                                          ContestScoreboardApplyLock applyLock,
                                          ContestScoreboardMySqlPollMetrics metrics,
                                          int chunkSize,
                                          int maxIterations) {
        this(ledger, application, detector, applyLock, metrics, chunkSize, maxIterations,
                ContestScoreboardExperimentTrace.NOOP);
    }

    public ContestScoreboardRangeRecovery(ContestScoreboardSequenceLedger ledger,
                                          ContestScoreboardSequencedApplication application,
                                          ContestScoreboardRollbackDetector detector,
                                          ContestScoreboardApplyLock applyLock,
                                          ContestScoreboardMySqlPollMetrics metrics,
                                          int chunkSize,
                                          int maxIterations,
                                          ContestScoreboardExperimentTrace trace) {
        this.trace = trace;
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
        if (!trace.enabled()) {
            return recoverRange(range);
        }
        String detail = "range-recovery (" + range.fromExclusive() + ", " + range.throughInclusive()
                + "] generation " + range.generation();
        long startedAt = System.currentTimeMillis();
        trace.recovery(passRecord(RecoveryEvent.PASS_START, startedAt, -1L, detail, "started"));
        String outcome = "failed";
        try {
            RangeResult result = recoverRange(range);
            outcome = result.outcome().name();
            return result;
        } finally {
            trace.recovery(passRecord(RecoveryEvent.PASS_END, startedAt, System.currentTimeMillis(), detail, outcome));
        }
    }

    private RangeResult recoverRange(ContestScoreboardRecoveryRange range) {
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
            ContestScoreboardSequencedApplication.ChunkOutcome outcome = applyChunk(range, rows);
            applied += outcome.applied();
            metrics.recordRecovered(outcome.applied());
            if (outcome.rolledBack()) {
                detector.check("recovery-refusal");
                return new RangeResult(Outcome.INTERRUPTED, applied);
            }
        }
        metrics.recordUnresolved();
        log.error("Scoreboard sequence range ({}, {}] generation {} still has results after {} chunk(s); it stays"
                        + " pending and the next recovery pass continues it", range.fromExclusive(),
                range.throughInclusive(), range.generation(), maxIterations);
        return new RangeResult(Outcome.UNRESOLVED, applied);
    }

    private ContestScoreboardSequencedApplication.ChunkOutcome applyChunk(ContestScoreboardRecoveryRange range,
                                                                        List<ContestScoreboardSequencedResult> rows) {
        if (!trace.enabled()) {
            return applyLock.withLock(() -> application.applyChunk(rows, range.throughInclusive()));
        }
        long requestedAt = System.currentTimeMillis();
        long[] lockedAt = {-1L};
        String outcome = "failed";
        int applied = rows.size();
        try {
            ContestScoreboardSequencedApplication.ChunkOutcome result = applyLock.withLock(() -> {
                lockedAt[0] = System.currentTimeMillis();
                return application.applyChunk(rows, range.throughInclusive());
            });
            applied = result.applied();
            outcome = result.rolledBack() ? "refused" : "applied";
            return result;
        } finally {
            trace.recovery(new RecoveryRecord(RecoveryEvent.CHUNK, Thread.currentThread().getName(), requestedAt,
                    lockedAt[0], System.currentTimeMillis(), applied,
                    "range generation " + range.generation(), outcome));
        }
    }

    private static RecoveryRecord passRecord(RecoveryEvent event, long start, long end, String detail,
                                             String outcome) {
        return new RecoveryRecord(event, Thread.currentThread().getName(), start, -1L, end, -1, detail, outcome);
    }

    enum Outcome { COMPLETED, INTERRUPTED, UNRESOLVED }

    record RangeResult(Outcome outcome, int applied) {
    }
}
