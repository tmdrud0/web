package my.oj.web.perf.dto;

public record SolvedBenchResult(int offset,
                                int naiveRows,
                                double naiveMillis,
                                int optimizedRows,
                                double optimizedMillis,
                                long pageStartRank,
                                long bucketSolvedCount,
                                long bucketCumHigher) {
}
