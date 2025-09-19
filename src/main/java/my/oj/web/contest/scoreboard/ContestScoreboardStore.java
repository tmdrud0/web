package my.oj.web.contest.scoreboard;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;
import java.util.List;

public interface ContestScoreboardStore {

    void recordJudgement(long eventId,
                         long contestId,
                         long problemId,
                         long userId,
                         LocalDateTime contestStart,
                         LocalDateTime submittedTime,
                         SubmissionResult result);

    ContestScoreboardSnapshot snapshot(long contestId);

    List<ContestScoreboardEntry> currentRanking(long contestId);

    void reset(long contestId);
}
