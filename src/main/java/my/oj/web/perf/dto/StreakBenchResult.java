package my.oj.web.perf.dto;

public record StreakBenchResult(int offset, int naiveRows, double naiveMillis, int snapshotRows, double snapshotMillis) {
}
