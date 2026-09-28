package my.oj.web.contest.scoreboard.poll;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a chunk of judged results to Redis and records what Redis issued in MySQL. Shared by the
 * poller and the range recovery so both take one path through the scoreboard.
 *
 * <p>Must be called under {@code ContestScoreboardApplyLock}: the watermark read here is the expected
 * value handed to Redis, and only a writer holding the lock may move it.</p>
 *
 * <h2>The expected watermark</h2>
 *
 * <p>It starts at the MySQL watermark and rises to every sequence this chunk has been issued. Each apply
 * hands it to the script, which refuses to issue anything while the allocator is below it. Rising with
 * the chunk matters: a Redis restored to a snapshot taken in the middle of this chunk has an allocator at
 * or above the chunk's starting watermark, and only the raised value exposes it before a sequence the
 * chunk already used is issued again.</p>
 *
 * <h2>Partial chunks</h2>
 *
 * <p>Whatever Redis applied before a refusal or a failure is still recorded. Those sequences are real:
 * if Redis lost them in a restore, their being in MySQL is exactly what puts them inside the next
 * detected range; if it did not, they are simply applied. A marker transaction that fails leaves the
 * rows without a sequence, and the poller re-applies them - the script returns the sequence a processed
 * submission already holds, so that costs nothing.</p>
 *
 * <h2>Experiment trace</h2>
 *
 * <p>With the live-impact trace on, every chunk Redis applied is one live batch, stamped when the last
 * script of the chunk answered - before the MySQL marker, because the standings already reflect the rows.
 * An event's offset is the row's position in the delivery order: the sequence it held when it was read
 * ({@code scoreboard_applied_seq}), or the one just issued when it had none. A range recovery therefore
 * reports the sequence each lost result held inside the range, the way a re-read Stream event carries its
 * original offset.</p>
 */
@Slf4j
public class ContestScoreboardSequencedApplication {

    private final ContestScoreboardSequencedApplier applier;
    private final ContestScoreboardSequenceLedger ledger;
    private final ContestScoreboardExperimentTrace trace;

    public ContestScoreboardSequencedApplication(ContestScoreboardSequencedApplier applier,
                                                 ContestScoreboardSequenceLedger ledger) {
        this(applier, ledger, ContestScoreboardExperimentTrace.NOOP);
    }

    public ContestScoreboardSequencedApplication(ContestScoreboardSequencedApplier applier,
                                                 ContestScoreboardSequenceLedger ledger,
                                                 ContestScoreboardExperimentTrace trace) {
        this.applier = applier;
        this.ledger = ledger;
        this.trace = trace;
    }

    ChunkOutcome applyChunk(List<ContestScoreboardSequencedResult> rows, long resequenceFloor) {
        long expected = ledger.highestDurableSequence();
        Map<Long, Long> applied = new LinkedHashMap<>();
        boolean rolledBack = false;
        try {
            for (ContestScoreboardSequencedResult row : rows) {
                long sequence = applier.apply(row.toUpdate(), expected, resequenceFloor);
                if (sequence == ContestScoreboardSequencedApplier.ROLLBACK) {
                    rolledBack = true;
                    break;
                }
                applied.put(row.submissionId(), sequence);
                expected = Math.max(expected, sequence);
            }
        } catch (RuntimeException failure) {
            traceApplied(rows, applied);
            recordAfterFailure(applied, failure);
            throw failure;
        }
        traceApplied(rows, applied);
        ledger.recordApplied(applied);
        return new ChunkOutcome(applied.size(), rolledBack);
    }

    private void traceApplied(List<ContestScoreboardSequencedResult> rows, Map<Long, Long> applied) {
        if (!trace.enabled() || applied.isEmpty()) {
            return;
        }
        long appliedAt = System.currentTimeMillis();
        List<ContestScoreboardExperimentTrace.LiveEvent> events = new ArrayList<>(applied.size());
        for (ContestScoreboardSequencedResult row : rows) {
            Long issued = applied.get(row.submissionId());
            if (issued == null) {
                continue;
            }
            long offset = row.appliedSequence() != null ? row.appliedSequence() : issued;
            events.add(new ContestScoreboardExperimentTrace.LiveEvent(offset, row.submissionId(), null));
        }
        trace.liveBatchApplied(appliedAt, events);
    }

    private void recordAfterFailure(Map<Long, Long> applied, RuntimeException failure) {
        try {
            ledger.recordApplied(applied);
        } catch (RuntimeException markerFailure) {
            failure.addSuppressed(markerFailure);
            log.warn("Could not record {} applied scoreboard sequence(s) after a Redis failure; the rows stay"
                    + " unsequenced and the next poll re-applies them", applied.size(), markerFailure);
        }
    }

    /**
     * @param applied    results Redis applied and MySQL recorded
     * @param rolledBack whether Redis refused because its allocator was below the expected watermark
     */
    record ChunkOutcome(int applied, boolean rolledBack) {
    }
}
