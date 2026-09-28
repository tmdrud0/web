package my.oj.web.contest.scoreboard.poll;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The poll script's contract in memory, so the orchestration around it can be driven without Redis.
 * The Lua itself is pinned by {@code RedisContestScoreboardSequencedApplierRedisIntegrationTests}; every
 * branch here mirrors one there. {@link #snapshot()} and {@link #restore} stand in for an RDB snapshot.
 */
class InMemorySequencedScoreboard implements ContestScoreboardSequencedApplier {

    private long allocator;
    private Set<Long> processed = new HashSet<>();
    private Map<Long, Long> mapping = new HashMap<>();
    /** What the standings reflect: each scored submission and its result. */
    private Map<Long, SubmissionResult> scored = new LinkedHashMap<>();
    private final Map<Long, Integer> scoreCount = new HashMap<>();
    int applyCalls;
    Runnable beforeApply = () -> { };
    boolean failFence;
    int fenceCalls;

    @Override
    public long apply(ContestScoreboardUpdate update, long expectedWatermark, long resequenceFloor) {
        applyCalls++;
        beforeApply.run();
        if (update.result() == SubmissionResult.PENDING) {
            throw new IllegalArgumentException("The MySQL poller applies judged results only");
        }
        if (allocator < expectedWatermark) {
            return ROLLBACK;
        }
        long submissionId = update.contestSubmissionId();
        Long mapped = mapping.get(submissionId);
        long next = allocator + 1;
        if (mapped != null && mapped >= next) {
            next = mapped + 1;
        }
        if (processed.contains(submissionId)) {
            if (mapped != null && mapped > resequenceFloor) {
                return mapped;
            }
            allocator = next;
            mapping.put(submissionId, next);
            return next;
        }
        processed.add(submissionId);
        scored.put(submissionId, update.result());
        scoreCount.merge(submissionId, 1, Integer::sum);
        allocator = next;
        mapping.put(submissionId, next);
        return next;
    }

    @Override
    public long allocatorSequence() {
        return allocator;
    }

    @Override
    public long fenceAllocator(long atLeast) {
        fenceCalls++;
        if (failFence) {
            throw new IllegalStateException("Redis is away");
        }
        allocator = Math.max(allocator, atLeast);
        return allocator;
    }

    Snapshot snapshot() {
        return new Snapshot(allocator, new HashSet<>(processed), new HashMap<>(mapping), new LinkedHashMap<>(scored));
    }

    void restore(Snapshot snapshot) {
        allocator = snapshot.allocator();
        processed = new HashSet<>(snapshot.processed());
        mapping = new HashMap<>(snapshot.mapping());
        scored = new LinkedHashMap<>(snapshot.scored());
    }

    Map<Long, SubmissionResult> scored() {
        return scored;
    }

    int timesScored(long submissionId) {
        return scoreCount.getOrDefault(submissionId, 0);
    }

    record Snapshot(long allocator, Set<Long> processed, Map<Long, Long> mapping, Map<Long, SubmissionResult> scored) {
    }
}
