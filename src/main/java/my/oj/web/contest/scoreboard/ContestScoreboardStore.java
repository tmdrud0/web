package my.oj.web.contest.scoreboard;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ContestScoreboardStore {

    /**
     * Applies one judgement. {@code eventId} deduplicates repeated delivery of the same
     * judgement; {@code contestSubmissionId} identifies the attempt itself and breaks ties
     * between attempts made in the same contest minute. Applying a set of judgements in any
     * order, and any number of times, must reach the same scoreboard state.
     */
    void recordJudgement(long eventId,
                         long contestSubmissionId,
                         long contestId,
                         long problemId,
                         long userId,
                         LocalDateTime contestStart,
                         LocalDateTime submittedTime,
                         SubmissionResult result);

    ContestScoreboardSnapshot snapshot(long contestId);

    default ContestScoreboardSlice topRanking(long contestId, int size) {
        return slice(contestId, 1, size);
    }

    ContestScoreboardSlice slice(long contestId, long startRank, int size);

    Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize);

    long totalParticipants(long contestId);

    void reset(long contestId);

    default List<ContestScoreboardEntry> currentRanking(long contestId) {
        return slice(contestId, 1, Integer.MAX_VALUE).entries();
    }
}
