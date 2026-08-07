package my.oj.web.contest.scoreboard.api;

import my.oj.web.contest.scoreboard.ContestScoreboardRanking;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;

import java.util.ArrayList;
import java.util.List;

/**
 * A page of the scoreboard, entries included.
 *
 * <p>The perf endpoint this replaces returned {@code entries().size()} and threw the rows away, so
 * a read that looked like the user's cost a fraction of it and the two were never comparable. This
 * carries what the caller asked for.
 *
 * <p>Rank is not stored on {@link my.oj.web.contest.scoreboard.ContestScoreboardEntry} - the slice
 * is ordered and knows where it starts, so a rank field would be a second copy of a fact already
 * in the ordering. Deriving it is not the same as counting positions, though: the rank a user is
 * shown is a competition rank over tied scores, and {@link ContestScoreboardRanking} is where that
 * rule lives so this and the rendered page cannot answer differently.
 *
 * <p>An entry carries the user id and not the user's name. The page resolves names with a MySQL
 * lookup over the hundred ids it is about to render; this endpoint reads Redis and nothing else,
 * which is the property that makes it worth measuring separately. That is a deliberate scope
 * reduction rather than parity: until a caller can get names from here, this is a scoreboard feed
 * and not yet a replacement for the page.
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
        long[] ranks = ContestScoreboardRanking.competitionRanks(slice);
        List<Entry> entries = new ArrayList<>(slice.entries().size());
        for (int i = 0; i < slice.entries().size(); i++) {
            var entry = slice.entries().get(i);
            entries.add(new Entry(ranks[i], entry.userId(), entry.solvedCount(), entry.penalty()));
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
