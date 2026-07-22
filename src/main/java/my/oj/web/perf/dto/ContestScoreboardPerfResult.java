package my.oj.web.perf.dto;

public record ContestScoreboardPerfResult(long contestId,
                                          long startRank,
                                          int size,
                                          int returnedCount,
                                          long totalParticipants,
                                          double elapsedMillis) {
}
