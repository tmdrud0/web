package my.oj.web.contest.scoreboard;

import java.util.List;

public record ContestScoreboardSnapshot(long contestId, List<ContestScoreboardEntry> entries) {
}
