package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.contest.scoreboard.ContestScoreboardSnapshot;
import my.oj.web.contest.scoreboard.ContestScoreboardStore;
import my.oj.web.contest.scoreboard.ProblemAttempts;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryContestScoreboardStore implements ContestScoreboardStore {

    private final Map<Long, ContestState> contests = new ConcurrentHashMap<>();

    /**
     * Records one attempt and rewrites what its problem contributes to the user totals.
     * The contribution is recomputed from every attempt seen for the problem, so the final
     * state does not depend on the order the judgements arrived in. Mirrors the Redis
     * implementations.
     */
    @Override
    public void recordJudgement(long eventId,
                                long contestSubmissionId,
                                long contestId,
                                long problemId,
                                long userId,
                                LocalDateTime contestStart,
                                LocalDateTime submittedTime,
                                SubmissionResult result) {
        ContestState state = getContestState(contestId, contestStart);

        if (!state.processedEvents.add(eventId)) {
            return;
        }
        // An unjudged submission scores nothing and does not put the user on the board,
        // matching what the live Redis scoreboard does with a PENDING event.
        if (result == SubmissionResult.PENDING) {
            return;
        }

        UserState userState = state.users.computeIfAbsent(userId, id -> new UserState());
        synchronized (userState) {
            ProblemAttempts attempts = userState.problemAttempts.computeIfAbsent(problemId, id -> new ProblemAttempts());
            long contestMinutes = ContestScoreboardPolicy.computeContestMinutes(state.contestStart, submittedTime);
            if (result == SubmissionResult.ACCEPTED) {
                attempts.recordAccepted(contestMinutes, contestSubmissionId);
            } else {
                attempts.recordWrong(contestSubmissionId, contestMinutes);
            }

            // Only the difference against what this problem contributed before is applied.
            ProblemAttempts.ContributionChange change = attempts.applyContribution();
            userState.solvedCount += (int) change.solvedDelta();
            userState.penalty += change.penaltyDelta();
        }

        state.updateUserScore(userId, userState);
    }

    @Override
    public ContestScoreboardSnapshot snapshot(long contestId) {
        return new ContestScoreboardSnapshot(contestId, slice(contestId, 1, Integer.MAX_VALUE).entries());
    }

    @Override
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

    @Override
    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        ContestState state = contests.get(contestId);
        if (state == null) {
            return Optional.empty();
        }
        return state.sliceAroundUser(contestId, userId, windowSize);
    }

    @Override
    public long totalParticipants(long contestId) {
        ContestState state = contests.get(contestId);
        return state != null ? state.participantCount() : 0L;
    }

    @Override
    public void reset(long contestId) {
        contests.remove(contestId);
    }

    private ContestState getContestState(long contestId, LocalDateTime contestStart) {
        ContestState state = contests.computeIfAbsent(contestId, id -> new ContestState(contestStart));
        state.ensureContestStart(contestStart);
        return state;
    }

    private static class ContestState {
        private final Map<Long, UserState> users = new ConcurrentHashMap<>();
        private final Set<Long> processedEvents = ConcurrentHashMap.newKeySet();
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
        private final Map<Long, ProblemAttempts> problemAttempts = new ConcurrentHashMap<>();
        private int solvedCount;
        private long penalty;
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
