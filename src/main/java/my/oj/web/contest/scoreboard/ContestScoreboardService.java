package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
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

    public ContestScoreboardSlice slice(long contestId, long startRank, int size) {
        return store.slice(contestId, startRank, size);
    }

    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        return store.rankingAroundUser(contestId, userId, windowSize);
    }

    public void reset(long contestId) {
        store.reset(contestId);
    }
}
