package my.oj.web.contest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

import my.oj.web.contest.finalization.ContestFinalizationService;
import my.oj.web.contest.finalization.ContestRejudgeService;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.testsupport.LoadTestDatabaseHelper;
import my.oj.web.testsupport.ThroughputMetrics;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest
@ActiveProfiles("test")
@Tag("load-test")
abstract class AbstractContestFinalizationLoadTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractContestFinalizationLoadTest.class);

    private static final long CONTEST_ID = 1L;
    private static final int PROBLEM_COUNT = 5;
    private static final int USER_COUNT = positiveInt("contest.load.userCount", 10_000);
    private static final int TOTAL_SUBMISSIONS = positiveInt("contest.load.totalSubmissions", 10_000);
    private static final int BATCH_SIZE = positiveInt("contest.load.batchSize", 1_000);
    private static final Duration FINALIZATION_TIMEOUT = Duration.ofMinutes(2);

    private long seedDurationNanos;

    @Autowired
    private ContestFinalizationService contestFinalizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ContestRejudgeService contestRejudgeService;

    @Value("${loadtest.judge.mode}")
    private String judgeMode;

    @BeforeEach
    void setUp() {
        long seedStart = System.nanoTime();
        seedData();
        seedDurationNanos = System.nanoTime() - seedStart;
        ThroughputMetrics seedMetrics = ThroughputMetrics.of(TOTAL_SUBMISSIONS, seedDurationNanos);
        log.info("[{}] seedContest -> {} (rows={})",
                judgeMode,
                seedMetrics.summary("rows"),
                TOTAL_SUBMISSIONS);
    }

    @AfterEach
    void tearDown() {
        LoadTestDatabaseHelper.truncateTables(jdbcTemplate,
                "`contest_submission_outbox`",
                "`contest_final_score`",
                "`accepted_submission`",
                "`user_problem_guard`",
                "`submission`",
                "`contest_submission_result`",
                "`contest_submission`",
                "`problem`",
                "`contest`",
                "`user`"
        );
    }

    @Test
    void finalizeContest_handlesTenThousandSubmissions() {
        long expectedParticipants = Math.min(USER_COUNT, TOTAL_SUBMISSIONS);
        long start = System.nanoTime();
        assertTimeoutPreemptively(
                FINALIZATION_TIMEOUT,
                () -> contestFinalizationService.finalizeContest(CONTEST_ID)
        );
        long elapsed = System.nanoTime() - start;

        long contestSubmissions = queryCount("contest_submission");
        long contestResults = queryCount("contest_submission_result");
        long finalScore = queryCount("contest_final_score");
        long accepted = queryCount("accepted_submission");
        long expectedAccepted = "fiftyFifty".equalsIgnoreCase(judgeMode) ? TOTAL_SUBMISSIONS / 2 : TOTAL_SUBMISSIONS;

        ThroughputMetrics metrics = ThroughputMetrics.of(TOTAL_SUBMISSIONS, elapsed);
        log.info("[{}] finalizeContest -> {} (participants={})",
                judgeMode,
                metrics.summary("submissions"),
                expectedParticipants);

        log.info("[{}] timings -> seed={} finalize={}",
                judgeMode,
                ThroughputMetrics.of(TOTAL_SUBMISSIONS, seedDurationNanos).summary("rows"),
                metrics.summary("submissions"));

        Assertions.assertThat(finalScore).isEqualTo(expectedParticipants);
        Assertions.assertThat(accepted).isEqualTo(expectedAccepted);
        Assertions.assertThat(metrics.perSecond()).isGreaterThan(0.0);
    }

    private void seedData() {
        LoadTestDatabaseHelper.truncateTables(jdbcTemplate,
                "`contest_submission_outbox`",
                "`contest_final_score`",
                "`accepted_submission`",
                "`user_problem_guard`",
                "`submission`",
                "`contest_submission_result`",
                "`contest_submission`",
                "`problem`",
                "`contest`",
                "`user`"
        );

        LocalDateTime contestStart = LocalDateTime.of(2025, 9, 1, 9, 0);
        LocalDateTime contestEnd = contestStart.plusHours(4);

        insertUsers(contestStart);
        insertContest(contestStart, contestEnd);
        insertProblems();
        insertContestSubmissions(contestStart);
        insertContestResults(contestStart);
    }

    private void insertUsers(LocalDateTime referenceTime) {
        String sql = """
                INSERT INTO `user` (id, name, pass, solved_count, streak_last_solved_date, streak_current_streak, streak_longest_streak)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        LocalDateTime lastSolved = referenceTime.minusDays(1);

        batch(sql, USER_COUNT, (ps, index) -> {
            long userId = index + 1L;
            ps.setLong(1, userId);
            ps.setString(2, "contest_user_" + userId);
            ps.setString(3, "pass");
            ps.setLong(4, 0L);
            ps.setTimestamp(5, Timestamp.valueOf(lastSolved));
            ps.setInt(6, 5);
            ps.setInt(7, 5);
        });
    }

    private void insertContest(LocalDateTime start, LocalDateTime end) {
        jdbcTemplate.update(
                "INSERT INTO contest (id, name, start_time, end_time) VALUES (?, ?, ?, ?)",
                CONTEST_ID,
                "Load Test Contest",
                Timestamp.valueOf(start),
                Timestamp.valueOf(end)
        );
    }

    private void insertProblems() {
        String sql = "INSERT INTO problem (id, name, contest_id, contest_num) VALUES (?, ?, ?, ?)";
        for (int i = 0; i < PROBLEM_COUNT; i++) {
            long problemId = i + 1L;
            jdbcTemplate.update(sql, problemId, "Problem " + problemId, CONTEST_ID, problemId);
        }
    }

    private void insertContestSubmissions(LocalDateTime contestStart) {
        String sql = """
                INSERT INTO contest_submission (id, contest_id, problem_id, user_id, submitted_time, code, code_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        batch(sql, TOTAL_SUBMISSIONS, (ps, index) -> {
            long submissionId = index + 1L;
            long userId = (index % USER_COUNT) + 1L;
            long problemId = (index % PROBLEM_COUNT) + 1L;
            LocalDateTime submittedAt = contestStart.plusSeconds(index % 7_200);

            ps.setLong(1, submissionId);
            ps.setLong(2, CONTEST_ID);
            ps.setLong(3, problemId);
            ps.setLong(4, userId);
            ps.setTimestamp(5, Timestamp.valueOf(submittedAt));
            ps.setString(6, "// code " + submissionId);
            ps.setString(7, String.format("%064x", submissionId));
        });
    }

    private void insertContestResults(LocalDateTime contestStart) {
        String sql = """
                INSERT INTO contest_submission_result (submission_id, contest_id, provisional_result, provisional_judged_at, final_result, final_judged_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        batch(sql, TOTAL_SUBMISSIONS, (ps, index) -> {
            long submissionId = index + 1L;
            LocalDateTime judgedAt = contestStart.plusSeconds(index % 7_200 + 120);
            SubmissionResult result = determineResult(submissionId);

            ps.setLong(1, submissionId);
            ps.setLong(2, CONTEST_ID);
            ps.setString(3, result.name());
            ps.setTimestamp(4, Timestamp.valueOf(judgedAt));
            ps.setString(5, result.name());
            ps.setTimestamp(6, Timestamp.valueOf(judgedAt));
        });
    }

    private SubmissionResult determineResult(long submissionId) {
        if ("fiftyFifty".equalsIgnoreCase(judgeMode)) {
            return submissionId % 2 == 0 ? SubmissionResult.ACCEPTED : SubmissionResult.WRONG_ANSWER;
        }
        return SubmissionResult.ACCEPTED;
    }

    private long queryCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static int positiveInt(String property, int defaultValue) {
        int value = Integer.getInteger(property, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    private void batch(String sql, int total, SqlSetter setter) {
        for (int start = 0; start < total; start += BATCH_SIZE) {
            int batchSize = Math.min(BATCH_SIZE, total - start);
            int offset = start;
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    setter.set(ps, offset + i);
                }

                @Override
                public int getBatchSize() {
                    return batchSize;
                }
            });
        }
    }

    @FunctionalInterface
    private interface SqlSetter {
        void set(PreparedStatement ps, int index) throws SQLException;
    }

}
