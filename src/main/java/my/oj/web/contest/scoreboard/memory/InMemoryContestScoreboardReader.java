package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.ContestScoreboardReader;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.contest.scoreboard.ContestScoreboardSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryContestScoreboardReader implements ContestScoreboardReader {

    private final InMemoryContestScoreboard scoreboard;

    public InMemoryContestScoreboardReader(InMemoryContestScoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }

    @Override
    public ContestScoreboardSnapshot snapshot(long contestId) {
        return new ContestScoreboardSnapshot(contestId, scoreboard.currentRanking(contestId));
    }

    @Override
    public ContestScoreboardSlice slice(long contestId, long startRank, int size) {
        return scoreboard.slice(contestId, startRank, size);
    }

    @Override
    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        return scoreboard.rankingAroundUser(contestId, userId, windowSize);
    }

    @Override
    public long totalParticipants(long contestId) {
        return scoreboard.totalParticipants(contestId);
    }
}
