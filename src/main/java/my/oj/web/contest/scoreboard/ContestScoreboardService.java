package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only facade over the live scoreboard. Writes go through
 * {@link ContestScoreboardApplier}.
 */
@Service
@RequiredArgsConstructor
public class ContestScoreboardService {

    private final ContestScoreboardReader reader;

    public List<ContestScoreboardEntry> currentRanking(long contestId) {
        return reader.currentRanking(contestId);
    }

    public ContestScoreboardSlice slice(long contestId, long startRank, int size) {
        return reader.slice(contestId, startRank, size);
    }

    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        return reader.rankingAroundUser(contestId, userId, windowSize);
    }
}
