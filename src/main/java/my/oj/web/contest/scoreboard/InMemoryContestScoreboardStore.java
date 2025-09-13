package my.oj.web.contest.scoreboard;

import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryContestScoreboardStore implements ContestScoreboardStore {

    private final Map<Long, ContestState> contests = new ConcurrentHashMap<>();

    @Override
    public void recordJudgement(long eventId,
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

        UserState userState = state.users.computeIfAbsent(userId, id -> new UserState());
        ProblemState problemState = userState.problemStates.computeIfAbsent(problemId, id -> new ProblemState());

        if (problemState.accepted) {
            return;
        }

        if (result == SubmissionResult.ACCEPTED) {
            problemState.accepted = true;
            problemState.acceptedTime = submittedTime;
            long penalty = ContestScoreboardMath.computePenalty(state.contestStart, submittedTime, problemState.wrongAttempts);
            userState.penalty += penalty;
            userState.solvedCount += 1;
        } else if (result != SubmissionResult.PENDING) {
            problemState.wrongAttempts += 1;
        }
    }

    @Override
    public ContestScoreboardSnapshot snapshot(long contestId) {
        return new ContestScoreboardSnapshot(contestId, currentRanking(contestId));
    }

    @Override
    public List<ContestScoreboardEntry> currentRanking(long contestId) {
        ContestState state = contests.get(contestId);
        if (state == null) {
            return Collections.emptyList();
        }

        List<ContestScoreboardEntry> entries = new ArrayList<>();
        state.users.forEach((userId, userState) ->
                entries.add(new ContestScoreboardEntry(userId, userState.solvedCount, userState.penalty))
        );

        entries.sort(Comparator
                .comparingInt(ContestScoreboardEntry::solvedCount).reversed()
                .thenComparingLong(ContestScoreboardEntry::penalty)
                .thenComparingLong(ContestScoreboardEntry::userId));
        return entries;
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
    }

    private static class UserState {
        private final Map<Long, ProblemState> problemStates = new ConcurrentHashMap<>();
        private int solvedCount;
        private long penalty;
    }

    private static class ProblemState {
        private int wrongAttempts;
        private boolean accepted;
        private LocalDateTime acceptedTime;
    }
}
