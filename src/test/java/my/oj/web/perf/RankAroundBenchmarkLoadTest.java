package my.oj.web.perf;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("load-test")
class RankAroundBenchmarkLoadTest {

    private static final Logger log = LoggerFactory.getLogger(RankAroundBenchmarkLoadTest.class);

    private static final String JDBC_URL = System.getProperty(
            "rank.bench.jdbcUrl",
            "jdbc:mysql://localhost:3306/oj_rank_bench?rewriteBatchedStatements=true&cachePrepStmts=true"
    );
    private static final String JDBC_USER = System.getProperty("rank.bench.jdbcUser", "root");
    private static final String JDBC_PASSWORD = System.getProperty("rank.bench.jdbcPassword", "1234");
    private static final int SAMPLE_COUNT = positiveInt("rank.bench.samples", 1_000);
    private static final int PAGE_SIZE = positiveInt("rank.bench.pageSize", 100);
    private static final long RANDOM_SEED = Long.getLong("rank.bench.randomSeed", 42L);

    @Test
    void solvedAround_randomSampleComparison_logsSummary() throws Exception {
        try (Connection connection = open()) {
            long total = queryForLong(connection, "SELECT COUNT(*) FROM `user` WHERE solved_count > 0");
            assertThat(total).isPositive();

            try (SolvedBench bench = new SolvedBench(connection)) {
                Summary summary = benchmarkRandomRanks(total, RANDOM_SEED, bench::run);
                logSummary("solved", total, summary);
            }
        }
    }

    @Test
    void currentStreakAround_randomSampleComparison_logsSummary() throws Exception {
        try (Connection connection = open()) {
            long total = queryForLong(connection, "SELECT COUNT(*) FROM user_streak_rank_snapshot");
            assertThat(total).isPositive();

            try (CurrentBench bench = new CurrentBench(connection)) {
                Summary summary = benchmarkRandomRanks(total, RANDOM_SEED + 1, bench::run);
                logSummary("current-streak", total, summary);
            }
        }
    }

    @Test
    void longestStreakAround_randomSampleComparison_logsSummary() throws Exception {
        try (Connection connection = open()) {
            long total = queryForLong(connection, "SELECT COUNT(*) FROM longest_streak_rank_snapshot");
            assertThat(total).isPositive();

            try (LongestBench bench = new LongestBench(connection)) {
                Summary summary = benchmarkRandomRanks(total, RANDOM_SEED + 2, bench::run);
                logSummary("longest-streak", total, summary);
            }
        }
    }

    private Summary benchmarkRandomRanks(long total,
                                         long seed,
                                         RankBenchFunction benchFn) throws SQLException {
        int samples = (int) Math.min(total, SAMPLE_COUNT);
        Random random = new Random(seed);
        List<Double> optimized = new ArrayList<>(samples);
        List<Double> naive = new ArrayList<>(samples);
        List<Double> speedups = new ArrayList<>(samples);

        for (int i = 0; i < samples; i++) {
            long rank = 1 + random.nextLong(total);
            BenchResult result = benchFn.run(rank);
            optimized.add(result.optimizedMillis());
            naive.add(result.naiveMillis());
            if (result.optimizedMillis() > 0.0) {
                speedups.add(result.naiveMillis() / result.optimizedMillis());
            }
        }

        return new Summary(
                samples,
                summarize(optimized),
                summarize(naive),
                summarize(speedups)
        );
    }

    private void logSummary(String label, long total, Summary summary) {
        log.info(
                "rank-around bench [{}] totalRanks={} samples={} pageSize={} | optimized={} | naive={} | speedup={}",
                label,
                total,
                summary.samples(),
                PAGE_SIZE,
                summary.optimized().format("ms"),
                summary.naive().format("ms"),
                summary.speedup().format("x")
        );
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
    }

    private static long queryForLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("No rows returned: " + sql);
            }
            return rs.getLong(1);
        }
    }

    private static int consumeRows(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            return rows;
        }
    }

    private static Stats summarize(List<Double> values) {
        if (values.isEmpty()) {
            return Stats.empty();
        }
        List<Double> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();

        double sum = 0.0;
        for (double value : sorted) {
            sum += value;
        }

        return new Stats(
                sorted.get(0),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sum / sorted.size(),
                sorted.get(sorted.size() - 1)
        );
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        int clamped = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(clamped);
    }

    private static int positiveInt(String property, int defaultValue) {
        int value = Integer.getInteger(property, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    private interface JdbcBench extends AutoCloseable {
        BenchResult run(long rank) throws SQLException;

        @Override
        void close() throws SQLException;
    }

    @FunctionalInterface
    private interface RankBenchFunction {
        BenchResult run(long rank) throws SQLException;
    }

    private static final class SolvedBench implements JdbcBench {
        private final PreparedStatement bucketLookup;
        private final PreparedStatement optimizedPage;
        private final PreparedStatement naivePage;

        private SolvedBench(Connection connection) throws SQLException {
            this.bucketLookup = connection.prepareStatement("""
                    SELECT b.n, b.cum_higher_count
                    FROM solved_count_bucket b
                    WHERE b.cum_higher_count < ?
                      AND ? <= b.cum_higher_count + b.user_count
                    ORDER BY b.n DESC
                    LIMIT 1
                    """);
            this.optimizedPage = connection.prepareStatement("""
                    SELECT u.id, u.name, u.solved_count, u.streak_last_solved_date
                    FROM `user` u FORCE INDEX (idx_user_ranking)
                    WHERE u.solved_count <= ?
                    ORDER BY u.solved_count DESC, u.streak_last_solved_date ASC, u.id ASC
                    LIMIT ? OFFSET ?
                    """);
            this.naivePage = connection.prepareStatement("""
                    SELECT u.id, u.name, u.solved_count, u.streak_last_solved_date
                    FROM `user` u FORCE INDEX (idx_user_ranking)
                    ORDER BY u.solved_count DESC, u.streak_last_solved_date ASC, u.id ASC
                    LIMIT ? OFFSET ?
                    """);
        }

        @Override
        public BenchResult run(long rank) throws SQLException {
            long pageStart = ((rank - 1) / PAGE_SIZE) * PAGE_SIZE + 1;

            long optimizedStart = System.nanoTime();
            bucketLookup.setLong(1, pageStart);
            bucketLookup.setLong(2, pageStart);
            long solvedCount;
            long cumHigher;
            try (ResultSet rs = bucketLookup.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Missing bucket for rank=" + pageStart);
                }
                solvedCount = rs.getLong(1);
                cumHigher = rs.getLong(2);
            }
            int offsetInBucket = (int) Math.max(0, pageStart - (cumHigher + 1));
            optimizedPage.setLong(1, solvedCount);
            optimizedPage.setInt(2, PAGE_SIZE);
            optimizedPage.setInt(3, offsetInBucket);
            int optimizedRows = consumeRows(optimizedPage);
            long optimizedEnd = System.nanoTime();

            long naiveStart = System.nanoTime();
            naivePage.setInt(1, PAGE_SIZE);
            naivePage.setLong(2, pageStart - 1);
            int naiveRows = consumeRows(naivePage);
            long naiveEnd = System.nanoTime();

            assertThat(optimizedRows).isPositive();
            assertThat(naiveRows).isPositive();

            return new BenchResult(nanosToMillis(optimizedStart, optimizedEnd), nanosToMillis(naiveStart, naiveEnd));
        }

        @Override
        public void close() throws SQLException {
            bucketLookup.close();
            optimizedPage.close();
            naivePage.close();
        }
    }

    private static final class CurrentBench implements JdbcBench {
        private final PreparedStatement optimizedPage;
        private final PreparedStatement naivePage;

        private CurrentBench(Connection connection) throws SQLException {
            this.optimizedPage = connection.prepareStatement("""
                    SELECT s.snapshot_rank, s.user_id, u.name, s.current_streak, s.last_solved_time
                    FROM user_streak_rank_snapshot s
                    JOIN `user` u ON u.id = s.user_id
                    WHERE s.snapshot_rank BETWEEN ? AND ?
                    ORDER BY s.snapshot_rank
                    """);
            this.naivePage = connection.prepareStatement("""
                    SELECT u.id, u.name, u.streak_current_streak, u.streak_last_solved_date
                    FROM `user` u FORCE INDEX (idx_user_current_streak)
                    WHERE u.streak_current_streak > 0
                    ORDER BY u.streak_current_streak DESC, u.streak_last_solved_date ASC, u.id ASC
                    LIMIT ? OFFSET ?
                    """);
        }

        @Override
        public BenchResult run(long rank) throws SQLException {
            long pageStart = ((rank - 1) / PAGE_SIZE) * PAGE_SIZE + 1;
            long pageEnd = pageStart + PAGE_SIZE - 1;

            long optimizedStart = System.nanoTime();
            optimizedPage.setLong(1, pageStart);
            optimizedPage.setLong(2, pageEnd);
            int optimizedRows = consumeRows(optimizedPage);
            long optimizedEnd = System.nanoTime();

            long naiveStart = System.nanoTime();
            naivePage.setInt(1, PAGE_SIZE);
            naivePage.setLong(2, pageStart - 1);
            int naiveRows = consumeRows(naivePage);
            long naiveEnd = System.nanoTime();

            assertThat(optimizedRows).isPositive();
            assertThat(naiveRows).isPositive();

            return new BenchResult(nanosToMillis(optimizedStart, optimizedEnd), nanosToMillis(naiveStart, naiveEnd));
        }

        @Override
        public void close() throws SQLException {
            optimizedPage.close();
            naivePage.close();
        }
    }

    private static final class LongestBench implements JdbcBench {
        private final PreparedStatement optimizedPage;
        private final PreparedStatement naivePage;

        private LongestBench(Connection connection) throws SQLException {
            this.optimizedPage = connection.prepareStatement("""
                    SELECT s.snapshot_rank, s.user_id, s.longest_streak, s.last_solved_time
                    FROM longest_streak_rank_snapshot s
                    WHERE s.snapshot_rank BETWEEN ? AND ?
                    ORDER BY s.snapshot_rank
                    """);
            this.naivePage = connection.prepareStatement("""
                    SELECT u.id, u.name, u.streak_longest_streak, u.streak_last_solved_date
                    FROM `user` u FORCE INDEX (idx_user_longest_streak)
                    ORDER BY u.streak_longest_streak DESC, u.streak_last_solved_date ASC, u.id ASC
                    LIMIT ? OFFSET ?
                    """);
        }

        @Override
        public BenchResult run(long rank) throws SQLException {
            long pageStart = ((rank - 1) / PAGE_SIZE) * PAGE_SIZE + 1;
            long pageEnd = pageStart + PAGE_SIZE - 1;

            long optimizedStart = System.nanoTime();
            optimizedPage.setLong(1, pageStart);
            optimizedPage.setLong(2, pageEnd);
            int optimizedRows = consumeRows(optimizedPage);
            long optimizedEnd = System.nanoTime();

            long naiveStart = System.nanoTime();
            naivePage.setInt(1, PAGE_SIZE);
            naivePage.setLong(2, pageStart - 1);
            int naiveRows = consumeRows(naivePage);
            long naiveEnd = System.nanoTime();

            assertThat(optimizedRows).isPositive();
            assertThat(naiveRows).isPositive();

            return new BenchResult(nanosToMillis(optimizedStart, optimizedEnd), nanosToMillis(naiveStart, naiveEnd));
        }

        @Override
        public void close() throws SQLException {
            optimizedPage.close();
            naivePage.close();
        }
    }

    private static double nanosToMillis(long start, long end) {
        return (end - start) / 1_000_000.0;
    }

    private record BenchResult(double optimizedMillis, double naiveMillis) {
    }

    private record Summary(int samples, Stats optimized, Stats naive, Stats speedup) {
    }

    private record Stats(double min, double p50, double p95, double avg, double max) {
        private static Stats empty() {
            return new Stats(0.0, 0.0, 0.0, 0.0, 0.0);
        }

        private String format(String unit) {
            return String.format(
                    "min=%.3f%s p50=%.3f%s p95=%.3f%s avg=%.3f%s max=%.3f%s",
                    min, unit,
                    p50, unit,
                    p95, unit,
                    avg, unit,
                    max, unit
            );
        }
    }
}
