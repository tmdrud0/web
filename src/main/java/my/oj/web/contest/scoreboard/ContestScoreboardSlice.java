package my.oj.web.contest.scoreboard;

import java.util.List;

public record ContestScoreboardSlice(long contestId,
                                     long startRank,
                                     List<ContestScoreboardEntry> entries,
                                     long totalParticipants) {

    public ContestScoreboardSlice {
        entries = List.copyOf(entries);
    }

    public long endRank() {
        if (entries.isEmpty()) {
            return startRank - 1;
        }
        return startRank + entries.size() - 1;
    }
}

