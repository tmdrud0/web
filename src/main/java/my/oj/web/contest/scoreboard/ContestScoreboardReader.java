package my.oj.web.contest.scoreboard;

import java.util.List;
import java.util.Optional;

/**
 * Read side of the live scoreboard. Every mutation goes through
 * {@link ContestScoreboardApplier} instead, so there is exactly one implementation of the
 * scoring rules per backing store rather than one per caller.
 */
public interface ContestScoreboardReader {

    ContestScoreboardSnapshot snapshot(long contestId);

    default ContestScoreboardSlice topRanking(long contestId, int size) {
        return slice(contestId, 1, size);
    }

    ContestScoreboardSlice slice(long contestId, long startRank, int size);

    Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize);

    long totalParticipants(long contestId);

    default List<ContestScoreboardEntry> currentRanking(long contestId) {
        return slice(contestId, 1, Integer.MAX_VALUE).entries();
    }
}
