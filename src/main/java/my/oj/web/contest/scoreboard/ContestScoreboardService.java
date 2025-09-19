package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

    public ContestScoreboardSnapshot snapshot(long contestId) {
        return store.snapshot(contestId);
    }

    public void reset(long contestId) {
        store.reset(contestId);
    }
}
