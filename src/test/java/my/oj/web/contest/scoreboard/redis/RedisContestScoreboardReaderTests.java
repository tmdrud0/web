package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standings are seeded directly rather than judged, because judging is the applier's job now.
 * {@link #standing} writes exactly what the Lua script leaves behind for one user.
 */
class RedisContestScoreboardReaderTests {

    private InMemoryContestRedisKeyValueClient redisClient;
    private RedisContestScoreboardReader reader;

    @BeforeEach
    void setUp() {
        redisClient = new InMemoryContestRedisKeyValueClient();
        reader = new RedisContestScoreboardReader(redisClient);
    }

    @Test
    void rankingOrdersBySolvedThenPenalty() {
        long contestId = 7L;
        standing(contestId, 1001L, 1, 30);
        standing(contestId, 2002L, 1, 5);
        standing(contestId, 3003L, 2, 90);

        List<ContestScoreboardEntry> ranking = reader.currentRanking(contestId);

        assertThat(ranking).containsExactly(
                new ContestScoreboardEntry(3003L, 2, 90),
                new ContestScoreboardEntry(2002L, 1, 5),
                new ContestScoreboardEntry(1001L, 1, 30)
        );
    }

    @Test
    void emptyContestReportsNoParticipants() {
        assertThat(reader.currentRanking(404L)).isEmpty();
        assertThat(reader.snapshot(404L).entries()).isEmpty();
        assertThat(reader.totalParticipants(404L)).isZero();
        assertThat(reader.rankingAroundUser(404L, 1L, 5)).isEmpty();
    }

    @Test
    void topRankingRespectsRequestedSize() {
        long contestId = 21L;
        for (int i = 1; i <= 5; i++) {
            standing(contestId, i, 1, i * 5L);
        }

        ContestScoreboardSlice topThree = reader.topRanking(contestId, 3);

        assertThat(topThree.startRank()).isEqualTo(1);
        assertThat(topThree.entries()).hasSize(3);
        assertThat(topThree.totalParticipants()).isEqualTo(5);
        assertThat(topThree.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void sliceReturnsRequestedWindow() {
        long contestId = 23L;
        for (int i = 1; i <= 8; i++) {
            standing(contestId, i, 1, i * 2L);
        }

        ContestScoreboardSlice slice = reader.slice(contestId, 4, 3);

        assertThat(slice.startRank()).isEqualTo(4);
        assertThat(slice.entries()).hasSize(3);
        assertThat(slice.totalParticipants()).isEqualTo(8);
        assertThat(slice.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(4L, 5L, 6L);
    }

    @Test
    void sliceBeyondTheLastRankReturnsNothingButStillReportsTheTotal() {
        long contestId = 24L;
        for (int i = 1; i <= 3; i++) {
            standing(contestId, i, 1, i);
        }

        ContestScoreboardSlice slice = reader.slice(contestId, 10, 5);

        assertThat(slice.entries()).isEmpty();
        assertThat(slice.startRank()).isEqualTo(10);
        assertThat(slice.totalParticipants()).isEqualTo(3);
    }

    @Test
    void rankingAroundUserCentersWindowWhenPossible() {
        long contestId = 22L;
        for (int i = 1; i <= 6; i++) {
            standing(contestId, i, 1, i * 3L);
        }

        ContestScoreboardSlice aroundUser = reader.rankingAroundUser(contestId, 4L, 3).orElseThrow();

        assertThat(aroundUser.startRank()).isEqualTo(3);
        assertThat(aroundUser.entries()).hasSize(3);
        assertThat(aroundUser.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(3L, 4L, 5L);
    }

    @Test
    void rankingAroundUserClampsToTheEndOfTheBoard() {
        long contestId = 25L;
        for (int i = 1; i <= 6; i++) {
            standing(contestId, i, 1, i * 3L);
        }

        ContestScoreboardSlice aroundUser = reader.rankingAroundUser(contestId, 6L, 3).orElseThrow();

        assertThat(aroundUser.startRank()).isEqualTo(4);
        assertThat(aroundUser.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(4L, 5L, 6L);
    }

    @Test
    void rankingAroundUserIsEmptyForSomeoneWhoHasNotSubmitted() {
        long contestId = 26L;
        standing(contestId, 1L, 1, 10);

        assertThat(reader.rankingAroundUser(contestId, 999L, 5)).isEmpty();
    }

    private void standing(long contestId, long userId, long solved, long penalty) {
        redisClient.zAdd(
                ContestScoreboardRedisKeys.ranking(contestId),
                ContestScoreboardPolicy.computeScore(solved, penalty, userId),
                Long.toString(userId)
        );
        String summaryKey = ContestScoreboardRedisKeys.summary(contestId, userId);
        redisClient.hSet(summaryKey, "solved", Long.toString(solved));
        redisClient.hSet(summaryKey, "penalty", Long.toString(penalty));
    }
}
