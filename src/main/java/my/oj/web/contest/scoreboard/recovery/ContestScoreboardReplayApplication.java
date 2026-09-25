package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Puts a chunk of rebuilt results on the scoreboard, and records in MySQL that it did.
 *
 * <p>Both recovery modes that re-apply stored results - {@code full-replay} and {@code redis-seq} -
 * go through here, so that the thing they have in common is written once. What they have in common is
 * the part that is easy to get wrong: the order of the two halves and the transaction around each.</p>
 *
 * <h2>The three steps, and why they are three</h2>
 *
 * <ol>
 *   <li>The chunk is applied to the scoreboard under {@link ContestScoreboardApplyLock}. This is
 *       Redis work and runs with <strong>no database transaction open</strong>, so the connection
 *       pool is not held for the duration of an {@code EVAL} that has nothing to do with the
 *       database.</li>
 *   <li>Every result is checked. A single refused event, or a batch that stopped short, fails the
 *       chunk before anything is recorded - the scoreboard is left as it is and the caller's retry
 *       re-applies, which is safe because re-applying a stored result is absorbed.</li>
 *   <li>Only then is the applied marker written, in its own short transaction.</li>
 * </ol>
 *
 * <p>The order between the last two is the whole point. The scoreboard write cannot be undone by a
 * database rollback, so recording first would leave MySQL claiming results the scoreboard never took,
 * and a recovery pass that trusted the marker would step over them. Recording second can only be
 * wrong in the other direction - a marker that never arrives - and that direction repairs itself: the
 * results look unapplied, so the next pass offers them again, the scoreboard absorbs them, and the
 * marker is written then.</p>
 *
 * <h2>Why the marker has its own retry</h2>
 *
 * <p>Because the two halves fail for different reasons and re-running them together is not free. The
 * caller's retry re-applies the chunk, which is harmless but is Redis work under the apply lock, on
 * the same lock the live stream path needs. A marker write that failed because of a database blip
 * needs the database, not another {@code EVAL}, so it is retried on its own with bounds that are its
 * own rather than the caller's chunk-replay bounds.</p>
 *
 * <p>When those attempts run out the failure is counted and logged rather than thrown. The chunk is
 * already on the scoreboard, and throwing would abandon the rest of the batch over a missing
 * timestamp that the next pass writes anyway.</p>
 */
@Component
@Slf4j
public class ContestScoreboardReplayApplication {

    /**
     * The marker's own bounds, deliberately not the caller's {@code retry-max-attempts}: those are
     * documented as the bounds of a chunk replay, and a chunk replay is not what is being retried.
     */
    private static final int MARKER_ATTEMPTS = 3;
    private static final Duration MARKER_BACKOFF = Duration.ofMillis(50);

    private final ContestScoreboardApplier scoreboardApplier;
    private final ContestScoreboardAppliedMarker appliedMarker;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestSubmissionBatchExecutor batchExecutor;
    private final Counter markerFailures;
    private final ContestScoreboardExperimentTrace trace;

    public ContestScoreboardReplayApplication(ContestScoreboardApplier scoreboardApplier,
                                              ContestScoreboardAppliedMarker appliedMarker,
                                              ContestScoreboardApplyLock applyLock,
                                              ContestSubmissionBatchExecutor batchExecutor,
                                              MeterRegistry meterRegistry) {
        this(scoreboardApplier, appliedMarker, applyLock, batchExecutor, meterRegistry,
                ContestScoreboardExperimentTrace.NOOP);
    }

    @Autowired
    public ContestScoreboardReplayApplication(ContestScoreboardApplier scoreboardApplier,
                                              ContestScoreboardAppliedMarker appliedMarker,
                                              ContestScoreboardApplyLock applyLock,
                                              ContestSubmissionBatchExecutor batchExecutor,
                                              MeterRegistry meterRegistry,
                                              ObjectProvider<ContestScoreboardExperimentTrace> trace) {
        this(scoreboardApplier, appliedMarker, applyLock, batchExecutor, meterRegistry,
                trace.getIfAvailable(() -> ContestScoreboardExperimentTrace.NOOP));
    }

    public ContestScoreboardReplayApplication(ContestScoreboardApplier scoreboardApplier,
                                              ContestScoreboardAppliedMarker appliedMarker,
                                              ContestScoreboardApplyLock applyLock,
                                              ContestSubmissionBatchExecutor batchExecutor,
                                              MeterRegistry meterRegistry,
                                              ContestScoreboardExperimentTrace trace) {
        this.trace = trace;
        this.scoreboardApplier = scoreboardApplier;
        this.appliedMarker = appliedMarker;
        this.applyLock = applyLock;
        this.batchExecutor = batchExecutor;
        this.markerFailures = Counter.builder("contest.scoreboard.recovery.marker.failed")
                .description("Replayed chunks whose applied marker could not be recorded in MySQL")
                .register(meterRegistry);
    }

    /**
     * Applies one chunk and records it.
     *
     * <p>The lock is taken per chunk rather than held across the whole replay, so a long recovery
     * delays the live stream path by at most one chunk and never while a caller is reading MySQL.</p>
     *
     * @param description what is being replayed, for the failure message and the log
     * @throws IllegalStateException if the scoreboard refused any result, or the batch stopped before
     *                               every result was applied
     */
    public void apply(List<ContestScoreboardApplier.ApplyRequest> requests, String description) {
        if (!trace.enabled()) {
            applyUnderLock(requests, description, null);
            return;
        }
        long requestedAt = System.currentTimeMillis();
        long[] lockedAt = {-1L};
        String outcome = "failed";
        try {
            applyUnderLock(requests, description, lockedAt);
            outcome = "applied";
        } finally {
            trace.recovery(new ContestScoreboardExperimentTrace.RecoveryRecord(
                    ContestScoreboardExperimentTrace.RecoveryEvent.CHUNK,
                    Thread.currentThread().getName(),
                    requestedAt,
                    lockedAt[0],
                    System.currentTimeMillis(),
                    requests.size(),
                    description,
                    outcome
            ));
        }
    }

    /**
     * @param lockedAt where the instant the lock was acquired is written, or {@code null} when nobody
     *                 is recording it
     */
    private void applyUnderLock(List<ContestScoreboardApplier.ApplyRequest> requests, String description,
                                long[] lockedAt) {
        applyLock.withLock(() -> {
            if (lockedAt != null) {
                lockedAt[0] = System.currentTimeMillis();
            }
            List<ContestScoreboardApplier.ApplyResult> results = scoreboardApplier.applyAll(requests);
            String failure = results.stream()
                    .filter(result -> !result.succeeded())
                    .map(ContestScoreboardApplier.ApplyResult::errorMessage)
                    .findFirst()
                    .orElse(null);
            if (failure != null || results.size() != requests.size()) {
                throw new IllegalStateException("Failed to replay " + description
                        + " onto the scoreboard: "
                        + (failure == null ? "batch stopped before every result was applied" : failure));
            }
            recordApplied(requests, description);
        });
    }

    /**
     * Writes the marker inside the apply lock.
     *
     * <p>Kept under the lock because the marker reads the sequence the scoreboard holds for each
     * result back out of Redis before writing it, and that read has to see the sequences this chunk
     * just issued - not a live event's, arriving between the two.</p>
     */
    private void recordApplied(List<ContestScoreboardApplier.ApplyRequest> requests, String description) {
        List<Long> submissionIds = requests.stream()
                .map(request -> request.update().contestSubmissionId())
                .toList();
        try {
            batchExecutor.executeWithRetry(
                    () -> batchExecutor.inNewTransaction(() -> appliedMarker.markApplied(submissionIds)),
                    MARKER_ATTEMPTS,
                    MARKER_BACKOFF
            );
        } catch (RuntimeException failure) {
            markerFailures.increment();
            log.error("Replayed {} onto the scoreboard but could not record it in MySQL; the results"
                    + " stay on the scoreboard, and the next pass re-offers them and writes the marker"
                    + " then", description, failure);
        }
    }
}
