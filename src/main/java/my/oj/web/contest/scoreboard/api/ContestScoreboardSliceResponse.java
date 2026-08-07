package my.oj.web.contest.scoreboard.api;

import my.oj.web.contest.scoreboard.ContestScoreboardSlice;

import java.util.List;

/**
 * A page of the scoreboard, entries included.
 *
 * <p>The perf endpoint this replaces returned {@code entries().size()} and threw the rows away, so
 * a read that looked like the user's cost a fraction of it and the two were never comparable. This
 * carries what the caller asked for.
 *
 * <p>Rank is derived from position rather than stored per entry: the slice is ordered and knows
 * where it starts, so a rank field on {@link my.oj.web.contest.scoreboard.ContestScoreboardEntry}
 * would be a second copy of that fact and could disagree with it.
 */
public record ContestScoreboardSliceResponse(long contestId,
                                             long startRank,
                                             long endRank,
                                             int size,
                                             long totalParticipants,
                                             List<Entry> entries) {

    public ContestScoreboardSliceResponse {
        entries = List.copyOf(entries);
    }

    public record Entry(long rank, long userId, int solvedCount, long penalty) {
    }

    public static ContestScoreboardSliceResponse from(ContestScoreboardSlice slice) {
        List<Entry> entries = new java.util.ArrayList<>(slice.entries().size());
        long rank = slice.startRank();
        for (var entry : slice.entries()) {
            entries.add(new Entry(rank++, entry.userId(), entry.solvedCount(), entry.penalty()));
        }
        return new ContestScoreboardSliceResponse(
                slice.contestId(),
                slice.startRank(),
                slice.endRank(),
                entries.size(),
                slice.totalParticipants(),
                entries
        );
    }
}
