package my.oj.web.contest.dto;

public record ContestScoreboardRow(
        int rank,
        long userId,
        String userName,
        int solvedCount,
        long penalty
) {
}
