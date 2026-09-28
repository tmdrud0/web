package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.CheckpointAdvance;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process mirror of the Redis offset, sequence and commutative scoreboard contract.
 *
 * <p>Implementing {@link ContestScoreboardSequenceSource} here is what lets the sequence state
 * machine be exercised without Redis: the allocator and the mapping behave as their Redis
 * counterparts do, so a test can drive the persistence and detection paths against a real MySQL
 * while the scoreboard itself stays in process.
 */
public class InMemoryContestScoreboardApplier implements ContestScoreboardApplier,
        ContestScoreboardSequenceSource {

    private final InMemoryContestScoreboard scoreboard;
    private final ContestScoreboardSequenceTracking sequenceTracking;
    private final ConcurrentMap<Long, Long> submissionSequences = new ConcurrentHashMap<>();
    private long sequenceAllocator;
    private long currentStreamOffset = -1L;

    public InMemoryContestScoreboardApplier(InMemoryContestScoreboard scoreboard) {
        this(scoreboard, ContestScoreboardSequenceTracking.DISABLED);
    }

    public InMemoryContestScoreboardApplier(InMemoryContestScoreboard scoreboard,
                                            ContestScoreboardSequenceTracking sequenceTracking) {
        this.scoreboard = scoreboard;
        this.sequenceTracking = sequenceTracking == null
                ? ContestScoreboardSequenceTracking.DISABLED
                : sequenceTracking;
    }

    @Override
    public synchronized Long apply(ApplyRequest request) {
        validate(request);
        return applyOne(request).appliedOffset();
    }

    /**
     * The batched script's contract, in process: in order, stopping at the first failure, refusing the
     * whole batch with {@link ApplyStatus#ROLLBACK} when the stored offset is below the caller's floor.
     * One lock hold covers the check and every write, which is what the single Lua invocation gives the
     * Redis store.
     */
    @Override
    public synchronized List<ApplyResult> applyAll(List<ApplyRequest> requests, long expectedCheckpointFloor) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ApplyRequest> safeRequests = new ArrayList<>(requests);
        boolean streamBatch = safeRequests.stream().anyMatch(request -> request != null && request.streamOffset() != null);
        if (streamBatch && expectedCheckpointFloor >= 0L && currentStreamOffset < expectedCheckpointFloor) {
            ApplyRequest first = safeRequests.get(0);
            return List.of(ApplyResult.rollback(first == null ? -1L : first.correlationId(), currentStreamOffset));
        }
        List<ApplyResult> results = new ArrayList<>(safeRequests.size());
        for (ApplyRequest request : safeRequests) {
            ApplyResult result;
            try {
                validate(request);
                result = applyOne(request);
            } catch (RuntimeException failure) {
                result = ApplyResult.failure(request == null ? -1L : request.correlationId(),
                        ContestScoreboardApplier.errorMessage(failure));
            }
            results.add(result);
            if (!result.succeeded()) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private ApplyResult applyOne(ApplyRequest request) {
        Long streamOffset = request.streamOffset();
        if (streamOffset != null) {
            if (streamOffset <= currentStreamOffset) {
                return ApplyResult.duplicate(request.correlationId(), currentStreamOffset);
            }
            // Mirrors the script's refusal. Offsets are not contiguous, so there is nothing to check
            // arithmetically; what the store owes is to reject a caller that did not say what it
            // verified, so an unclassified jump cannot pass here and fail against Redis.
            if (request.advance() == CheckpointAdvance.NONE) {
                throw new IllegalStateException(
                        "A stream request must classify the checkpoint advance it asks for");
            }
        }

        ContestScoreboardUpdate update = request.update();
        boolean alreadyProcessed = scoreboard.hasProcessed(update.contestId(), update.contestSubmissionId());
        boolean applied = scoreboard.apply(update);
        Long sequence = null;
        if (applied && sequenceTracking.enabled()) {
            sequence = issueSequence(update.contestSubmissionId());
        }
        if (streamOffset != null) {
            currentStreamOffset = streamOffset;
        }
        return alreadyProcessed
                ? ApplyResult.duplicate(request.correlationId(), currentStreamOffset)
                : ApplyResult.applied(request.correlationId(), currentStreamOffset, sequence);
    }

    /**
     * Mirrors the script's rule: a mapping at or above the next allocator value steps over it, so
     * the sequence stays strictly increasing even when the allocator was rewound past a mapping.
     */
    private long issueSequence(long submissionId) {
        Long mapped = submissionSequences.get(submissionId);
        long sequence = sequenceAllocator + 1L;
        if (mapped != null && mapped >= sequence) {
            sequence = mapped + 1L;
        }
        sequenceAllocator = sequence;
        submissionSequences.put(submissionId, sequence);
        return sequence;
    }

    @Override
    public synchronized Map<Long, Long> appliedSequences(Collection<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> sequences = new LinkedHashMap<>();
        for (Long submissionId : submissionIds) {
            Long sequence = submissionId == null ? null : submissionSequences.get(submissionId);
            if (sequence != null) {
                sequences.put(submissionId, sequence);
            }
        }
        return sequences;
    }

    @Override
    public synchronized long allocatorSequence() {
        return sequenceAllocator;
    }

    @Override
    public synchronized long mappedSubmissionCount() {
        return submissionSequences.size();
    }

    @Override
    public synchronized long currentStreamOffset() {
        return currentStreamOffset;
    }

    @Override
    public void reset(long contestId) {
        scoreboard.reset(contestId);
    }

    private static void validate(ApplyRequest request) {
        ContestScoreboardUpdate update = request == null ? null : request.update();
        if (request == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            throw new IllegalArgumentException("Scoreboard event and update fields are required");
        }
    }
}
