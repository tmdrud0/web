package my.oj.web.contest.scoreboard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContestScoreboardRankingTests {

    @Test
    void tiedEntriesShareARankAndTheTieSkipsTheRanksItConsumed() {
        ContestScoreboardSlice slice = slice(1L,
                entry(11L, 5, 100L),
                entry(12L, 5, 100L),
                entry(13L, 5, 100L),
                entry(14L, 4, 100L)
        );

        assertThat(ContestScoreboardRanking.competitionRanks(slice)).containsExactly(1L, 1L, 1L, 4L);
    }

    @Test
    void penaltyBreaksATieOnSolvedCount() {
        ContestScoreboardSlice slice = slice(1L,
                entry(11L, 5, 100L),
                entry(12L, 5, 200L),
                entry(13L, 5, 200L)
        );

        assertThat(ContestScoreboardRanking.competitionRanks(slice)).containsExactly(1L, 2L, 2L);
    }

    @Test
    void ranksAreAbsoluteRatherThanRelativeToTheSlice() {
        ContestScoreboardSlice slice = slice(101L,
                entry(11L, 3, 500L),
                entry(12L, 2, 500L)
        );

        assertThat(ContestScoreboardRanking.competitionRanks(slice)).containsExactly(101L, 102L);
    }

    /**
     * A tie that straddles a page boundary is not continued across it: the first entry of a page
     * takes that page's start rank even when it ties with the last entry of the page before. This
     * is the behaviour the rendered scoreboard has always had - continuing the tie would need the
     * entry before the slice, and fetching it is a Redis read added to every paged request - and
     * it is pinned here because it is the case where position-based and competition ranking agree
     * by accident, so nothing else would notice it changing.
     */
    @Test
    void aTieAcrossThePageBoundaryRestartsAtTheNewPagesStartRank() {
        List<ContestScoreboardEntry> firstPage = new ArrayList<>();
        for (long userId = 1; userId <= 99; userId++) {
            firstPage.add(entry(userId, 5, 100L));
        }
        firstPage.add(entry(100L, 3, 400L));
        ContestScoreboardSlice page1 = new ContestScoreboardSlice(7L, 1L, firstPage, 200L);
        ContestScoreboardSlice page2 = slice(101L,
                entry(101L, 3, 400L),
                entry(102L, 1, 900L)
        );

        long[] page1Ranks = ContestScoreboardRanking.competitionRanks(page1);
        long[] page2Ranks = ContestScoreboardRanking.competitionRanks(page2);

        assertThat(page1Ranks[99]).isEqualTo(100L);
        assertThat(page2Ranks[0]).isEqualTo(101L);
    }

    @Test
    void anEmptySliceHasNoRanks() {
        ContestScoreboardSlice slice = new ContestScoreboardSlice(7L, 4_000L, List.of(), 620L);

        assertThat(ContestScoreboardRanking.competitionRanks(slice)).isEmpty();
    }

    private static ContestScoreboardSlice slice(long startRank, ContestScoreboardEntry... entries) {
        return new ContestScoreboardSlice(7L, startRank, List.of(entries), 500L);
    }

    private static ContestScoreboardEntry entry(long userId, int solvedCount, long penalty) {
        return new ContestScoreboardEntry(userId, solvedCount, penalty);
    }
}
