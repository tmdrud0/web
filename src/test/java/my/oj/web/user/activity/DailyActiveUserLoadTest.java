package my.oj.web.user.activity;

import my.oj.web.testsupport.LoadTestDatabaseHelper;
import my.oj.web.testsupport.ThroughputMetrics;
import my.oj.web.user.rank.streak.StreakRankBatchService;
import my.oj.web.user.rank.streak.UserStreakRankSnapshotRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

@SpringBootTest
@ActiveProfiles("test")
@Tag("load-test")
class DailyActiveUserLoadTest {

    private static final Logger log = LoggerFactory.getLogger(DailyActiveUserLoadTest.class);

    private static final int USER_COUNT = positiveInt("streak.load.userCount", 10_000);
    private static final int OVERLAP_COUNT = Math.min(positiveInt("streak.load.overlapCount", 5_000), USER_COUNT);
    private static final int BATCH_SIZE = positiveInt("streak.load.batchSize", 1_000);

    private long seedDurationNanos;
    private long upsertDurationNanos;
    private long batchDurationNanos;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StreakRankBatchService streakRankBatchService;

    @Autowired
    private UserStreakRankSnapshotRepository snapshotRepository;

    private LocalDate targetDay;
    private LocalDate previousDay;
    private LocalDateTime baseTime;

    @BeforeEach
    void setUp() {
        LoadTestDatabaseHelper.truncateTables(
                jdbcTemplate,
                "user_streak_rank_snapshot",
                "daily_active_users",
                "`user`"
        );
        targetDay = LocalDate.now();
        previousDay = targetDay.minusDays(1);
        baseTime = LocalDateTime.now().withNano(0);

        long seedStart = System.nanoTime();
        insertUsers();
        insertPreviousDayDailyActive();
        seedDurationNanos = System.nanoTime() - seedStart;
        log.info("Seeded daily-active load fixtures -> {} (overlap={})",
                ThroughputMetrics.of(USER_COUNT, seedDurationNanos).summary("rows"),
                OVERLAP_COUNT);
    }

    @AfterEach
    void tearDown() {
        LoadTestDatabaseHelper.truncateTables(
                jdbcTemplate,
                "user_streak_rank_snapshot",
                "daily_active_users",
                "`user`"
        );
    }

    @Test
    void streakBatch_handlesTenThousandUsersWithFiveThousandOverlap() {
        long upsertStart = System.nanoTime();
        upsertDailyActiveUsers();
        upsertDurationNanos = System.nanoTime() - upsertStart;

        long batchStart = System.nanoTime();
        streakRankBatchService.rebuildFor(targetDay);
        batchDurationNanos = System.nanoTime() - batchStart;

        long todayCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM daily_active_users WHERE day = ?",
                Long.class,
                targetDay
        );

        long continuedUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE streak_current_streak = 2",
                Long.class
        );
        long newUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE streak_current_streak = 1",
                Long.class
        );
        long snapshotRows = snapshotRepository.count();

        ThroughputMetrics seedMetrics = ThroughputMetrics.of(USER_COUNT, seedDurationNanos);
        ThroughputMetrics upsertMetrics = ThroughputMetrics.of(USER_COUNT, upsertDurationNanos);
        ThroughputMetrics batchMetrics = ThroughputMetrics.of(USER_COUNT, batchDurationNanos);

        log.info("dailyActive load timings -> seed={}, upsert={}, batch={}",
                seedMetrics.summary("rows"),
                upsertMetrics.summary("rows"),
                batchMetrics.summary("rows"));

        Assertions.assertThat(todayCount).isEqualTo(USER_COUNT);
        Assertions.assertThat(continuedUsers).isEqualTo(OVERLAP_COUNT);
        Assertions.assertThat(newUsers).isEqualTo(USER_COUNT - OVERLAP_COUNT);
        Assertions.assertThat(snapshotRows).isEqualTo(USER_COUNT);
        Assertions.assertThat(upsertMetrics.perSecond()).isGreaterThan(0.0);
        Assertions.assertThat(batchMetrics.perSecond()).isGreaterThan(0.0);
    }

    private void insertUsers() {
        String sql = """
                INSERT INTO `user` (id, name, pass, solved_count, streak_last_solved_date, streak_current_streak, streak_longest_streak)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        LocalDateTime lastSolved = previousDay.atTime(21, 0);

        batch(sql, USER_COUNT, (ps, index) -> {
            long userId = index + 1L;
            ps.setLong(1, userId);
            ps.setString(2, "dau_user_" + userId);
            ps.setString(3, "pass");
            ps.setLong(4, 0L);
            ps.setTimestamp(5, Timestamp.valueOf(lastSolved));
            ps.setInt(6, 1);
            ps.setInt(7, 5);
        });
    }

    private void insertPreviousDayDailyActive() {
        if (OVERLAP_COUNT <= 0) {
            return;
        }
        String sql = """
                INSERT INTO daily_active_users (day, user_id, last_active_time)
                VALUES (?, ?, ?)
                """;

        batch(sql, OVERLAP_COUNT, (ps, index) -> {
            long userId = index + 1L;
            ps.setObject(1, previousDay);
            ps.setLong(2, userId);
            ps.setTimestamp(3, Timestamp.valueOf(previousDay.atTime(22, 0)));
        });
    }

    private void upsertDailyActiveUsers() {
        String sql = """
                INSERT INTO daily_active_users (day, user_id, last_active_time)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE last_active_time = VALUES(last_active_time)
                """;

        batch(sql, USER_COUNT, (ps, index) -> {
            long userId = index + 1L;
            ps.setObject(1, targetDay);
            ps.setLong(2, userId);
            ps.setTimestamp(3, Timestamp.valueOf(baseTime.plusSeconds(index % 900)));
        });
    }

    private void batch(String sql, int total, SqlSetter setter) {
        for (int start = 0; start < total; start += BATCH_SIZE) {
            int size = Math.min(BATCH_SIZE, total - start);
            int offset = start;
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    setter.set(ps, offset + i);
                }

                @Override
                public int getBatchSize() {
                    return size;
                }
            });
        }
    }

    @FunctionalInterface
    private interface SqlSetter {
        void set(PreparedStatement ps, int index) throws SQLException;
    }

    private static int positiveInt(String property, int defaultValue) {
        int value = Integer.getInteger(property, defaultValue);
        return value > 0 ? value : defaultValue;
    }
}
