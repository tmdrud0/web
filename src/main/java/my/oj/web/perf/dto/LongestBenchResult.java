package my.oj.web.perf.dto;

public record LongestBenchResult(int offset,
                                 int naiveRows,
                                 double naiveMillis,
                                 int optimizedRows,
                                 double optimizedMillis,
                                 long pageStartRank,
                                 int bucketLongest,
                                 long bucketCumHigher) {
}
