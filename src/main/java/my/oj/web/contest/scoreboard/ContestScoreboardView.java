package my.oj.web.contest.scoreboard;

import my.oj.web.contest.dto.ContestScoreboardRow;

import java.util.List;

/**
 * A cold scoreboard view: rows with names and ranks, plus where the caller is in the standings.
 *
 * <p>Cursors are nulls rather than {@code Optional}, because this is serialised: an absent cursor
 * means there is no page in that direction, and {@code null} says that in JSON where an empty
 * {@code Optional} does not.
 */
public record ContestScoreboardView(List<ContestScoreboardRow> rows,
                                    long startRank,
                                    long totalParticipants,
                                    int pageSize,
                                    boolean aroundMe,
                                    Long previousCursor,
                                    Long nextCursor,
                                    Long cursor) {

    public ContestScoreboardView {
        rows = List.copyOf(rows);
    }

    static ContestScoreboardView empty(int pageSize) {
        return new ContestScoreboardView(List.of(), 1, 0, pageSize, false, null, null, null);
    }
}
