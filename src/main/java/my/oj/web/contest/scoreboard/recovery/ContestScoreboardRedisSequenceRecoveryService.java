package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.submission.core.ContestScoreboardDuplicateSequence;
import my.oj.web.contest.submission.core.ContestScoreboardSequencedRow;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code redis-seq} recovery mode: find the results whose sequence was reused or left behind,
 * and re-apply only those.
 *
 * <p>Conditional on the mode, unlike the full-replay service: nothing else runs a sequence check, and
 * the sequence state it reads exists only beside a Redis scoreboard. Leaving it unconditional would
 * make the default {@code store=memory} configuration fail to start for want of a sequence source.</p>
 *
 * <h2>Why the read order is the whole of the correctness</h2>
 *
 * <p>A sequence reaches MySQL after the scoreboard issued it, so the two comparisons the mode makes
 * are only sound in one direction:</p>
 *
 * <ul>
 *   <li>{@code stored sequence > allocator} means the allocator moved backwards, because a sequence
 *       cannot be stored before it was issued. Reading the allocator first and then finding stored
 *       rows above it would flag every result an in-flight batch had just been issued - healthy
 *       results reported as lost.</li>
 *   <li>Which is why every database read in a round completes before the allocator is read at all,
 *       including the window walk that follows the descending sequence. Judging each window against
 *       a freshly read allocator would let the allocator advance between two windows of the same
 *       round and misjudge the deeper one.</li>
 * </ul>
 *
 * <h2>What a round does</h2>
 *
 * <ol>
 *   <li>Duplicate groups, walked in ascending sequence windows. A group is a reuse, and reuse is
 *       what a rewound allocator leaves behind.</li>
 *   <li>The sequenced tail, walked in descending sequence windows, kept aside unfiltered.</li>
 *   <li>Then, and only then, the allocator: the rows kept aside that sit above it are the lost
 *       tail.</li>
 *   <li>Replay every candidate through the same apply path the live stream uses, which is what
 *       makes a mode switch a change of checkpoint rather than a second scoring implementation.</li>
 * </ol>
 *
 * <p>The walk continues past the first window because a window alone leaves a hole: when the
 * allocator has fallen further than {@code check-window-size} results, the deepest missing rows are
 * outside the first page and would never become candidates.</p>
 *
 * <h2>Why a round repeats, and when it stops</h2>
 *
 * <p>A worker can advance the allocator between the database read and the allocator read, and a
 * rollback can leave one sequence on more than one result. Re-checking converges because a replayed
 * result is issued a sequence at or below the allocator by construction: the allocator is set to the
 * sequence just issued, so the replayed row is no longer above it and the next round finds nothing.
 * A group whose results the scoreboard has already processed does not converge that way - replaying
 * them is absorbed, which is a state this mode can find but not repair - so the rounds are bounded
 * by {@code max-iterations} and a pass that spends them all is reported instead of spinning.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "contest.scoreboard.recovery",
        name = "mode",
        havingValue = "redis-seq"
)
@RequiredArgsConstructor
@Slf4j
public class ContestScoreboardRedisSequenceRecoveryService {

    private final ContestSubmissionResultRepository resultRepository;
    private final ContestScoreboardApplier scoreboardApplier;
    private final ContestScoreboardSequenceSource sequenceSource;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardAppliedMarker appliedMarker;
    private final ContestSubmissionBatchExecutor batchExecutor;
    private final ContestScoreboardRecoveryProperties properties;
    private final ContestScoreboardRedisSequenceMetrics metrics;

    /**
     * Runs rounds until a round finds nothing to replay or {@code max-iterations} is spent.
     *
     * <p>Never called concurrently with itself: the scheduler and the startup check share one guard,
     * because two passes reading the allocator at overlapping moments would each judge the other's
     * in-flight results.</p>
     */
    public SequenceCheckReport check() {
        ContestScoreboardRecoveryProperties.RedisSequence config = properties.redisSeq();
        int rounds = 0;
        long duplicateGroups = 0;
        int replayed = 0;
        boolean saturated = false;
        boolean unresolved = false;
        while (true) {
            Round round = runRound(config);
            rounds++;
            duplicateGroups += round.duplicateGroups();
            replayed += round.replayed();
            saturated |= round.saturated();
            metrics.recordRound();
            if (round.candidates() == 0) {
                break;
            }
            if (rounds >= config.maxIterations()) {
                unresolved = true;
                break;
            }
        }
        metrics.recordDuplicates(duplicateGroups);
        metrics.recordReplayed(replayed);
        metrics.recordMappedSubmissions(sequenceSource.mappedSubmissionCount());
        if (saturated) {
            metrics.recordSaturatedWindows();
        }
        if (unresolved) {
            metrics.recordUnresolved();
        }
        return new SequenceCheckReport(rounds, duplicateGroups, replayed, saturated, unresolved);
    }

    private Round runRound(ContestScoreboardRecoveryProperties.RedisSequence config) {
        Map<Long, ContestScoreboardSequencedRow> candidates = new LinkedHashMap<>();

        DuplicateScan scan = scanDuplicateGroups(config, candidates);
        WindowWalk walk = walkSequencedTail(config);

        long allocator = sequenceSource.allocatorSequence();
        for (ContestScoreboardSequencedRow row : walk.rows()) {
            if (row.getAppliedSequence() > allocator) {
                candidates.putIfAbsent(row.getSubmissionId(), row);
            }
        }

        if (candidates.isEmpty()) {
            return new Round(scan.groups(), 0, scan.saturated() || walk.saturated(), 0);
        }

        List<ContestScoreboardSequencedRow> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator
                .comparing(ContestScoreboardSequencedRow::getAppliedSequence)
                .thenComparing(ContestScoreboardSequencedRow::getSubmissionId));
        return new Round(
                scan.groups(),
                replay(ordered, config),
                scan.saturated() || walk.saturated(),
                ordered.size()
        );
    }

    /**
     * Collects the results sharing a sequence, page by page.
     *
     * <p>Every row of a group is a candidate, not one per group: re-applying them is what gives each
     * row a sequence of its own, and the reuse is not resolved until none of them shares one.</p>
     *
     * <p>Paged for the same reason the tail is, and with the same window budget: a healthy system
     * reads one empty page and stops, and a reuse that runs deeper than the budget is reported
     * rather than silently half-collected.</p>
     */
    private DuplicateScan scanDuplicateGroups(ContestScoreboardRecoveryProperties.RedisSequence config,
                                              Map<Long, ContestScoreboardSequencedRow> candidates) {
        long groups = 0;
        Long afterSequence = null;
        for (int window = 0; window < config.maxWindowsPerPass(); window++) {
            List<ContestScoreboardDuplicateSequence> page = resultRepository.findDuplicateAppliedSequences(
                    afterSequence, PageRequest.of(0, config.checkWindowSize()));
            if (page.isEmpty()) {
                return new DuplicateScan(groups, false);
            }
            groups += page.size();
            afterSequence = page.get(page.size() - 1).getAppliedSequence();
            List<Long> sequences = page.stream()
                    .map(ContestScoreboardDuplicateSequence::getAppliedSequence)
                    .toList();
            for (ContestScoreboardSequencedRow row : resultRepository.findRowsByAppliedSequences(sequences)) {
                candidates.putIfAbsent(row.getSubmissionId(), row);
            }
            if (page.size() < config.checkWindowSize()) {
                return new DuplicateScan(groups, false);
            }
        }
        return new DuplicateScan(groups, true);
    }

    /**
     * Reads the sequenced results from the top down, and reports whether the budget ran out before
     * the set did.
     *
     * <p>Deliberately unfiltered: the allocator this walk's rows are judged against has not been read
     * yet, and must not be read until the walk is over.</p>
     *
     * <p>The keyset is the sequence itself. Two rows sharing a sequence would straddle a page
     * boundary and be missed by a strict {@code <} - except that two rows sharing a sequence is a
     * duplicate group, which the group scan collects in full without paging by that boundary. So
     * the one value the keyset cannot page over is the one the other scan covers exhaustively.</p>
     */
    private WindowWalk walkSequencedTail(ContestScoreboardRecoveryProperties.RedisSequence config) {
        List<ContestScoreboardSequencedRow> rows = new ArrayList<>();
        Long afterSequence = null;
        boolean saturated = false;
        for (int window = 0; window < config.maxWindowsPerPass(); window++) {
            List<ContestScoreboardSequencedRow> page = resultRepository.findSequencedRowsDescending(
                    afterSequence, PageRequest.of(0, config.checkWindowSize()));
            if (page.isEmpty()) {
                return new WindowWalk(rows, false);
            }
            rows.addAll(page);
            afterSequence = page.get(page.size() - 1).getAppliedSequence();
            if (page.size() < config.checkWindowSize()) {
                return new WindowWalk(rows, false);
            }
            saturated = true;
        }
        return new WindowWalk(rows, saturated);
    }

    /**
     * Re-applies the candidates in ascending sequence order, in chunks.
     *
     * <p>Ascending order is what the sequence means: the results the scoreboard applied first are
     * re-issued the lower sequences, so a replay leaves the scoreboard's own ordering intact rather
     * than permuting it with the order this walk happened to read in.</p>
     *
     * @return how many results were offered to the scoreboard, the already-applied ones included -
     *         the apply path, not this service, decides which of them change anything
     */
    private int replay(List<ContestScoreboardSequencedRow> candidates,
                       ContestScoreboardRecoveryProperties.RedisSequence config) {
        int chunkSize = config.replayBatchSize();
        int replayed = 0;
        for (int start = 0; start < candidates.size(); start += chunkSize) {
            List<ContestScoreboardSequencedRow> chunk = List.copyOf(
                    candidates.subList(start, Math.min(start + chunkSize, candidates.size())));
            batchExecutor.executeWithRetry(
                    () -> applyChunk(chunk),
                    config.retryMaxAttempts(),
                    config.retryBackoff()
            );
            replayed += chunk.size();
        }
        return replayed;
    }

    /**
     * The lock is taken per chunk rather than for the whole replay, so a long recovery delays the
     * live stream path by at most one chunk and never while this service is reading MySQL.
     *
     * <p>The marker is what persists the sequence the scoreboard just issued: it reads the mapping
     * back after the apply and writes it beside the timestamp, so a replayed result stops being a
     * candidate because the database now agrees with the scoreboard about its sequence.</p>
     */
    private void applyChunk(List<ContestScoreboardSequencedRow> chunk) {
        List<ContestScoreboardApplier.ApplyRequest> requests = chunk.stream()
                .map(ContestScoreboardRedisSequenceRecoveryService::request)
                .toList();
        applyLock.withLock(() -> {
            List<ContestScoreboardApplier.ApplyResult> results = scoreboardApplier.applyAll(requests);
            String failure = results.stream()
                    .filter(result -> !result.succeeded())
                    .map(ContestScoreboardApplier.ApplyResult::errorMessage)
                    .findFirst()
                    .orElse(null);
            if (failure != null || results.size() != requests.size()) {
                throw new IllegalStateException("Failed to replay sequenced results onto the scoreboard: "
                        + (failure == null ? "batch stopped before every result was applied" : failure));
            }
            appliedMarker.markApplied(requests.stream()
                    .map(request -> request.update().contestSubmissionId())
                    .toList());
        });
    }

    /**
     * A rebuild request carries no stream offset, which is what keeps a sequence replay from moving
     * the checkpoint the RDB snapshot restored. This mode repairs the tail; it does not claim stream
     * work it did not do.
     */
    private static ContestScoreboardApplier.ApplyRequest request(ContestScoreboardSequencedRow row) {
        return ContestScoreboardApplier.ApplyRequest.rebuild(
                row.getSubmissionId(),
                new ContestScoreboardUpdate(
                        row.getSubmissionId(),
                        row.getContestId(),
                        row.getProblemId(),
                        row.getUserId(),
                        row.getContestStart(),
                        row.getSubmittedTime(),
                        row.getResult(),
                        null
                )
        );
    }

    /** What one check pass saw and did. */
    public record SequenceCheckReport(int rounds,
                                      long duplicateGroups,
                                      int replayed,
                                      boolean saturated,
                                      boolean unresolved) {
    }

    private record Round(long duplicateGroups, int replayed, boolean saturated, int candidates) {
    }

    private record DuplicateScan(long groups, boolean saturated) {
    }

    private record WindowWalk(List<ContestScoreboardSequencedRow> rows, boolean saturated) {
    }
}
