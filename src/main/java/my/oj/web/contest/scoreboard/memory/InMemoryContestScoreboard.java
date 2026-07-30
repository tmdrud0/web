package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live scoreboard held in process. {@link InMemoryContestScoreboardReader} and
 * {@link InMemoryContestScoreboardApplier} are thin views over one of these, so both sides see
 * the same state.
 *
 * <p>Also usable standalone as a scratch buffer for replaying judgements off to the side —
 * see the final-score calculation.
 */
public class InMemoryContestScoreboard {

    private final Map<Long, ContestState> contests = new ConcurrentHashMap<>();
    /**
     * Mirrors the Redis sequence allocator: one number per submission, stable across retries
     * and rebuilds, and never rewound by {@link #reset(long)}.
     */
    private final Map<Long, Long> submissionSequences = new ConcurrentHashMap<>();
    private final AtomicLong sequenceAllocator = new AtomicLong();

    /**
     * Records one attempt and rewrites what its problem contributes to the user totals.
     * Recomputing from every attempt seen for the problem makes the result independent of
     * judgement arrival order.
     */
    public long apply(ContestScoreboardUpdate update) {
        long submissionId = update.contestSubmissionId();
        long sequence = submissionSequences.computeIfAbsent(
                submissionId,
                id -> sequenceAllocator.incrementAndGet()
        );

        ContestState state = getContestState(update.contestId(), update.contestStart());
        if (!state.appliedSubmissions.add(submissionId)) {
            return sequence;
        }
        if (update.result() == SubmissionResult.PENDING) {
            return sequence;
        }

        UserState userState = state.users.computeIfAbsent(update.userId(), id -> new UserState());
        synchronized (userState) {
            ProblemState problemState = userState.problemStates.computeIfAbsent(
                    update.problemId(),
                    id -> new ProblemState()
            );
            long contestMinutes = ContestScoreboardPolicy.computeContestMinutes(
                    state.contestStart,
                    update.submittedTime()
            );
            if (update.result() == SubmissionResult.ACCEPTED) {
                problemState.recordAcceptedIfEarliest(contestMinutes, submissionId);
            } else {
                problemState.recordWrong(submissionId, contestMinutes);
            }

            // Only the difference against what this problem contributed before is applied.
            ContestScoreboardPolicy.ProblemContribution contribution = problemState.contribution();
            int solvedDelta = (int) (contribution.solved() - problemState.contributedSolved);
            long penaltyDelta = contribution.penalty() - problemState.contributedPenalty;
            userState.solvedCount += solvedDelta;
            userState.penalty += penaltyDelta;
            problemState.contributedSolved = contribution.solved();
            problemState.contributedPenalty = contribution.penalty();
        }

        state.updateUserScore(update.userId(), userState);
        return sequence;
    }

    public long currentSequence() {
        return sequenceAllocator.get();
    }

    public void reset(long contestId) {
        contests.remove(contestId);
    }

    public ContestScoreboardSlice slice(long contestId, long startRank, int size) {
        long normalizedStart = Math.max(1, startRank);
        if (size <= 0) {
            return new ContestScoreboardSlice(contestId, normalizedStart, List.of(), totalParticipants(contestId));
        }
        ContestState state = contests.get(contestId);
        if (state == null) {
            return new ContestScoreboardSlice(contestId, normalizedStart, List.of(), 0);
        }
        return state.sliceFromRank(contestId, normalizedStart, size);
    }

    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        ContestState state = contests.get(contestId);
        if (state == null) {
            return Optional.empty();
        }
        return state.sliceAroundUser(contestId, userId, windowSize);
    }

    public long totalParticipants(long contestId) {
        ContestState state = contests.get(contestId);
        return state != null ? state.participantCount() : 0L;
    }

    public List<ContestScoreboardEntry> currentRanking(long contestId) {
        return slice(contestId, 1, Integer.MAX_VALUE).entries();
    }

    private ContestState getContestState(long contestId, LocalDateTime contestStart) {
        ContestState state = contests.computeIfAbsent(contestId, id -> new ContestState(contestStart));
        state.ensureContestStart(contestStart);
        return state;
    }

    private static class ContestState {
        private final Map<Long, UserState> users = new ConcurrentHashMap<>();
        private final Set<Long> appliedSubmissions = ConcurrentHashMap.newKeySet();
        private final NavigableSet<UserScore> ranking = new ConcurrentSkipListSet<>();
        private final Map<Long, UserScore> scoreIndex = new ConcurrentHashMap<>();
        private volatile LocalDateTime contestStart;

        private ContestState(LocalDateTime start) {
            this.contestStart = start;
        }

        private void ensureContestStart(LocalDateTime start) {
            if (start == null) {
                return;
            }
            if (contestStart == null || contestStart.isAfter(start)) {
                contestStart = start;
            }
        }

        private void updateUserScore(long userId, UserState userState) {
            UserScore updated = new UserScore(userId, userState.solvedCount, userState.penalty);
            synchronized (this) {
                UserScore previous = scoreIndex.put(userId, updated);
                if (previous != null) {
                    ranking.remove(previous);
                }
                ranking.add(updated);
            }
        }

        private ContestScoreboardSlice sliceFromRank(long contestId, long startRank, int size) {
            if (ranking.isEmpty() || size <= 0) {
                return new ContestScoreboardSlice(contestId, startRank, List.of(), ranking.size());
            }
            long total = ranking.size();
            if (startRank > total) {
                return new ContestScoreboardSlice(contestId, startRank, List.of(), total);
            }
            int skip = (int) Math.max(0, startRank - 1);
            int limit = Math.min(size, (int) Math.min(Integer.MAX_VALUE, total - skip));
            List<ContestScoreboardEntry> entries = new ArrayList<>(limit);
            int index = 0;
            for (UserScore score : ranking) {
                if (index++ < skip) {
                    continue;
                }
                entries.add(score.toEntry());
                if (entries.size() >= limit) {
                    break;
                }
            }
            return new ContestScoreboardSlice(contestId, startRank, entries, total);
        }

        private Optional<ContestScoreboardSlice> sliceAroundUser(long contestId,
                                                                 long userId,
                                                                 int windowSize) {
            UserScore target = scoreIndex.get(userId);
            if (target == null || ranking.isEmpty()) {
                return Optional.empty();
            }

            int total = ranking.size();
            int effectiveWindow = Math.max(1, Math.min(windowSize, total));

            List<UserScore> snapshot = new ArrayList<>(total);
            int targetIndex = -1;
            int idx = 0;
            for (UserScore score : ranking) {
                snapshot.add(score);
                if (score.equals(target)) {
                    targetIndex = idx;
                }
                idx++;
            }
            if (targetIndex < 0) {
                return Optional.empty();
            }

            int startIndex = Math.max(0, targetIndex - effectiveWindow / 2);
            if (startIndex + effectiveWindow > snapshot.size()) {
                startIndex = snapshot.size() - effectiveWindow;
            }
            long startRank = startIndex + 1L;

            List<ContestScoreboardEntry> entries = new ArrayList<>(effectiveWindow);
            for (int i = startIndex; i < startIndex + effectiveWindow; i++) {
                entries.add(snapshot.get(i).toEntry());
            }
            return Optional.of(new ContestScoreboardSlice(contestId, startRank, entries, snapshot.size()));
        }

        private long participantCount() {
            return ranking.size();
        }
    }

    private static class UserState {
        private final Map<Long, ProblemState> problemStates = new ConcurrentHashMap<>();
        private int solvedCount;
        private long penalty;
    }

    /** Every attempt seen for one (user, problem), plus what that problem last contributed. */
    private static class ProblemState {
        private final Map<Long, Long> wrongMinutesBySubmissionId = new HashMap<>();
        private Long acceptedMinutes;
        private Long acceptedSubmissionId;
        private long contributedSolved;
        private long contributedPenalty;

        private void recordAcceptedIfEarliest(long contestMinutes, long contestSubmissionId) {
            if (acceptedMinutes != null && !ContestScoreboardPolicy.isEarlierAttempt(
                    contestMinutes,
                    contestSubmissionId,
                    acceptedMinutes,
                    acceptedSubmissionId
            )) {
                return;
            }
            acceptedMinutes = contestMinutes;
            acceptedSubmissionId = contestSubmissionId;
        }

        private void recordWrong(long contestSubmissionId, long contestMinutes) {
            wrongMinutesBySubmissionId.put(contestSubmissionId, contestMinutes);
        }

        private ContestScoreboardPolicy.ProblemContribution contribution() {
            return ContestScoreboardPolicy.computeProblemContribution(
                    acceptedMinutes,
                    acceptedSubmissionId,
                    wrongMinutesBySubmissionId
            );
        }
    }

    private static final class UserScore implements Comparable<UserScore> {
        private final long userId;
        private final int solvedCount;
        private final long penalty;

        private UserScore(long userId, int solvedCount, long penalty) {
            this.userId = userId;
            this.solvedCount = solvedCount;
            this.penalty = penalty;
        }

        private ContestScoreboardEntry toEntry() {
            return new ContestScoreboardEntry(userId, solvedCount, penalty);
        }

        @Override
        public int compareTo(UserScore other) {
            int solvedCompare = Integer.compare(other.solvedCount, this.solvedCount);
            if (solvedCompare != 0) {
                return solvedCompare;
            }
            int penaltyCompare = Long.compare(this.penalty, other.penalty);
            if (penaltyCompare != 0) {
                return penaltyCompare;
            }
            return Long.compare(this.userId, other.userId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof UserScore other)) {
                return false;
            }
            return userId == other.userId
                    && solvedCount == other.solvedCount
                    && penalty == other.penalty;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(userId);
            result = 31 * result + Integer.hashCode(solvedCount);
            result = 31 * result + Long.hashCode(penalty);
            return result;
        }
    }
}
