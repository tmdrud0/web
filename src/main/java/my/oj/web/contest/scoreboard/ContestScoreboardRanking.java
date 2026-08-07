package my.oj.web.contest.scoreboard;

import java.util.List;

/**
 * The rank a scoreboard entry is displayed with.
 *
 * <p>Competition ranking: entries tied on {@code (solvedCount, penalty)} share the rank of the
 * first of them and the ranks the tie consumes are skipped, so four entries where the first three
 * are tied read 1, 1, 1, 4. Ties are not an edge case here - a contest of five problems scored on
 * solved count and penalty puts most of the field on a handful of distinct scores.
 *
 * <p>This lives beside the slice rather than in either caller because the page and the JSON API
 * answer the same question about the same Redis state, and a second implementation of the rule is
 * free to disagree with the first. It did: the API numbered entries by position, so one scoreboard
 * returned 1, 1, 1, 4 through {@code /contests/{id}?tab=scoreboard} and 1, 2, 3, 4 through
 * {@code /api/contests/{id}/scoreboard}.
 *
 * <p>Ranks are computed within the slice, so the first entry always takes {@code startRank} even
 * when it ties with the last entry of the page before it. Continuing a tie across the boundary
 * would need the entry preceding the slice, which is another Redis read on every paged request;
 * the page has always numbered pages this way and this keeps that.
 */
public final class ContestScoreboardRanking {

    private ContestScoreboardRanking() {
    }

    /**
     * Ranks for {@code slice.entries()}, index for index.
     */
    public static long[] competitionRanks(ContestScoreboardSlice slice) {
        List<ContestScoreboardEntry> entries = slice.entries();
        long[] ranks = new long[entries.size()];
        long displayRank = slice.startRank();
        int previousSolved = Integer.MIN_VALUE;
        long previousPenalty = Long.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            ContestScoreboardEntry entry = entries.get(i);
            if (entry.solvedCount() != previousSolved || entry.penalty() != previousPenalty) {
                displayRank = slice.startRank() + i;
                previousSolved = entry.solvedCount();
                previousPenalty = entry.penalty();
            }
            ranks[i] = displayRank;
        }
        return ranks;
    }
}
