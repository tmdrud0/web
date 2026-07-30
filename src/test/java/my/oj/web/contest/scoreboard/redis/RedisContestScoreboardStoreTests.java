package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisContestScoreboardStoreTests {

    private RedisContestScoreboardStore store;

    @BeforeEach
    void setUp() {
        store = new RedisContestScoreboardStore(new FakeContestRedisKeyValueClient());
    }

    @Test
    void recordJudgementBuildsSortedRanking() {
        long contestId = 7L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 9, 0);

        store.recordJudgement(1L, 1L, contestId, 11L, 1001L, start, start.plusMinutes(15), SubmissionResult.WRONG_ANSWER);
        store.recordJudgement(2L, 2L, contestId, 11L, 1001L, start, start.plusMinutes(25), SubmissionResult.ACCEPTED);
        store.recordJudgement(3L, 3L, contestId, 12L, 2002L, start, start.plusMinutes(5), SubmissionResult.ACCEPTED);

        List<ContestScoreboardEntry> ranking = store.currentRanking(contestId);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0)).isEqualTo(new ContestScoreboardEntry(2002L, 1, 5));
        assertThat(ranking.get(1)).isEqualTo(new ContestScoreboardEntry(1001L, 1, 30));
    }

    @Test
    void resetRemovesContestState() {
        long contestId = 9L;
        LocalDateTime start = LocalDateTime.of(2024, 5, 1, 10, 0);

        store.recordJudgement(10L, 10L, contestId, 21L, 3333L, start, start.plusMinutes(12), SubmissionResult.ACCEPTED);
        assertThat(store.currentRanking(contestId)).isNotEmpty();

        store.reset(contestId);

        assertThat(store.currentRanking(contestId)).isEmpty();
        assertThat(store.snapshot(contestId).entries()).isEmpty();
    }

    // Duplicate delivery, arrival order and PENDING are covered for both store implementations
    // by ContestScoreboardStoreCommutativityTests.

    @Test
    void topRankingRespectsRequestedSize() {
        long contestId = 21L;
        LocalDateTime start = LocalDateTime.of(2024, 8, 1, 9, 0);

        for (int i = 1; i <= 5; i++) {
            long userId = i;
            store.recordJudgement(1000L + i, 1000L + i, contestId, 90L + i, userId, start, start.plusMinutes(i * 5L), SubmissionResult.ACCEPTED);
        }

        ContestScoreboardSlice topThree = store.topRanking(contestId, 3);

        assertThat(topThree.startRank()).isEqualTo(1);
        assertThat(topThree.entries()).hasSize(3);
        assertThat(topThree.totalParticipants()).isEqualTo(5);
        assertThat(topThree.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void sliceReturnsRequestedWindow() {
        long contestId = 23L;
        LocalDateTime start = LocalDateTime.of(2024, 8, 3, 9, 0);

        for (int i = 1; i <= 8; i++) {
            long userId = i;
            store.recordJudgement(3000L + i, 3000L + i, contestId, 200L + i, userId, start, start.plusMinutes(i * 2L), SubmissionResult.ACCEPTED);
        }

        ContestScoreboardSlice slice = store.slice(contestId, 4, 3);

        assertThat(slice.startRank()).isEqualTo(4);
        assertThat(slice.entries()).hasSize(3);
        assertThat(slice.totalParticipants()).isEqualTo(8);
        assertThat(slice.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(4L, 5L, 6L);
    }

    @Test
    void rankingAroundUserCentersWindowWhenPossible() {
        long contestId = 22L;
        LocalDateTime start = LocalDateTime.of(2024, 8, 2, 9, 0);

        for (int i = 1; i <= 6; i++) {
            long userId = i;
            store.recordJudgement(2000L + i, 2000L + i, contestId, 100L + i, userId, start, start.plusMinutes(i * 3L), SubmissionResult.ACCEPTED);
        }

        var aroundUser = store.rankingAroundUser(contestId, 4L, 3).orElseThrow();

        assertThat(aroundUser.startRank()).isEqualTo(3);
        assertThat(aroundUser.entries()).hasSize(3);
        assertThat(aroundUser.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(3L, 4L, 5L);
    }
}
