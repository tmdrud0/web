package my.oj.web.perf.dto;

public record ContestSeedResult(
        long contestId,
        int problemCount,
        long firstProblemId,
        long lastProblemId,
        int userCount,
        long firstUserId,
        long lastUserId
) {
}

