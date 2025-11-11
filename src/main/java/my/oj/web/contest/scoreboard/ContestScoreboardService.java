package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Primary
@RequiredArgsConstructor
public class ContestScoreboardService {

    private final ContestScoreboardStore store;

    public void recordJudgement(long eventId,
                                long contestId,
                                long problemId,
                                long userId,
                                LocalDateTime contestStart,
                                LocalDateTime submittedTime,
                                SubmissionResult result) {
        store.recordJudgement(eventId, contestId, problemId, userId, contestStart, submittedTime, result);
    }

    public List<ContestScoreboardEntry> currentRanking(long contestId) {
        return store.currentRanking(contestId);
    }

    public ContestScoreboardSnapshot snapshot(long contestId) {
        return store.snapshot(contestId);
    }

    public ContestScoreboardSlice topRanking(long contestId, int size) {
        return slice(contestId, 1, size);
    }

    public ContestScoreboardSlice slice(long contestId, long startRank, int size) {
        return store.slice(contestId, startRank, size);
    }

    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        return store.rankingAroundUser(contestId, userId, windowSize);
    }

    public long totalParticipants(long contestId) {
        return store.totalParticipants(contestId);
    }

    public void reset(long contestId) {
        store.reset(contestId);
    }
}
