package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

import java.util.Collection;
import java.util.LinkedHashMap;
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
        Long streamOffset = request.streamOffset();
        if (streamOffset != null) {
            if (streamOffset <= currentStreamOffset) {
                return currentStreamOffset;
            }
            if (!request.allowOffsetGap() && streamOffset != currentStreamOffset + 1L) {
                throw new IllegalStateException(
                        "Non-contiguous scoreboard stream offset: expected "
                                + (currentStreamOffset + 1L) + " but received " + streamOffset
                );
            }
        }

        boolean applied = scoreboard.apply(request.update());
        if (applied && sequenceTracking.enabled()) {
            issueSequence(request.update().contestSubmissionId());
        }
        if (streamOffset != null) {
            currentStreamOffset = streamOffset;
        }
        return currentStreamOffset;
    }

    /**
     * Mirrors the script's rule: a mapping at or above the next allocator value steps over it, so
     * the sequence stays strictly increasing even when the allocator was rewound past a mapping.
     */
    private void issueSequence(long submissionId) {
        Long mapped = submissionSequences.get(submissionId);
        long sequence = sequenceAllocator + 1L;
        if (mapped != null && mapped >= sequence) {
            sequence = mapped + 1L;
        }
        sequenceAllocator = sequence;
        submissionSequences.put(submissionId, sequence);
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
