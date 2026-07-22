package my.oj.web.contest.scoreboard.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.outbox.immediate.enabled=false",
        "contest.outbox.scheduler.enabled=false",
        "rank.streak.batch.enabled=false",
        "contest.submission.post-process.enabled=false",
        "contest.submission.judge.event-listener.enabled=false",
        "contest.submission.judge.scheduler.enabled=false",
        "contest.submission.judge.rabbit.publisher.enabled=false",
        "contest.submission.judge.rabbit.listener.enabled=false"
})
@Tag("load-test")
@EnabledIfEnvironmentVariable(named = "INCLUDE_SCOREBOARD_LOAD_TEST", matches = "true")
class ContestScoreboardOutboxPipelineLoadTests {

    private static final int TOTAL_EVENTS = 10_000;
    private static final int USER_COUNT = 1_000;
    private static final int BATCH_SIZE = 500;

    @DynamicPropertySource
    static void loadTestProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault(
                "MYSQL_SCOREBOARD_LOAD_TEST_URL",
                "jdbc:mysql://localhost:3306/oj_codex_scoreboard_pipeline_20260718"
                        + "?createDatabaseIfNotExist=true&rewriteBatchedStatements=true&cachePrepStmts=true"
        ));
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.parseInt(
                System.getenv().getOrDefault("SCOREBOARD_LOAD_TEST_REDIS_PORT", "16379")
        ));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ContestScoreboardOutboxProcessor processor;

    @Autowired
    private ContestScoreboardOutboxProcessLock processLock;

    @AfterEach
    void tearDown() {
        flushRedis();
        jdbcTemplate.update("DELETE FROM contest_submission_outbox");
        jdbcTemplate.update("DELETE FROM contest_submission_result");
        jdbcTemplate.update("DELETE FROM contest_judge_outbox");
        jdbcTemplate.update("DELETE FROM contest_submission");
        jdbcTemplate.update("DELETE FROM problem");
        jdbcTemplate.update("DELETE FROM contest");
        jdbcTemplate.update("DELETE FROM `user`");
    }

    @Test
    void drainsScoreboardOutboxWithPipelinedRedisAndBatchCompletion() {
        flushRedis();
        seedOutbox(TOTAL_EVENTS, USER_COUNT);

        long startedAt = System.nanoTime();
        int claimed = 0;
        int completed = 0;
        int failed = 0;
        int stale = 0;
        int batches = 0;
        while (true) {
            ContestScoreboardOutboxProcessor.BatchProcessResult result = processLock.executeIfAcquired(
                    () -> processor.processBatch(BATCH_SIZE, Duration.ofSeconds(30))
            ).orElseThrow(() -> new IllegalStateException("Scoreboard process lock was unexpectedly busy"));
            if (result.claimed() == 0) {
                break;
            }
            claimed += result.claimed();
            completed += result.completed();
            failed += result.failed();
            stale += result.stale();
            batches++;
        }
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        double throughput = completed / elapsedSeconds;
        Long completedInDatabase = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_outbox WHERE status = 'COMPLETED'",
                Long.class
        );

        System.out.println("scoreboard-outbox-pipeline-summary"
                + " events=" + TOTAL_EVENTS
                + " claimed=" + claimed
                + " completed=" + completed
                + " failed=" + failed
                + " stale=" + stale
                + " batches=" + batches
                + " batchSize=" + BATCH_SIZE
                + " elapsedSeconds=" + String.format(Locale.ROOT, "%.3f", elapsedSeconds)
                + " throughput=" + String.format(Locale.ROOT, "%.1f", throughput));

        assertThat(claimed).isEqualTo(TOTAL_EVENTS);
        assertThat(completed).isEqualTo(TOTAL_EVENTS);
        assertThat(completedInDatabase).isEqualTo(TOTAL_EVENTS);
        assertThat(failed).isZero();
        assertThat(stale).isZero();
        assertThat(batches).isEqualTo(TOTAL_EVENTS / BATCH_SIZE);
    }

    private void seedOutbox(int eventCount, int userCount) {
        String suffix = UUID.randomUUID().toString();
        LocalDateTime contestStart = LocalDateTime.of(2026, 3, 10, 12, 0);
        jdbcTemplate.update(
                "INSERT INTO contest (name, start_time, end_time) VALUES (?, ?, ?)",
                "scoreboard-pipeline-" + suffix,
                contestStart,
                contestStart.plusHours(2)
        );
        Long contestId = jdbcTemplate.queryForObject(
                "SELECT id FROM contest WHERE name = ?",
                Long.class,
                "scoreboard-pipeline-" + suffix
        );
        jdbcTemplate.update(
                "INSERT INTO problem (name, contest_id, contest_num) VALUES (?, ?, 1)",
                "scoreboard-pipeline-problem-" + suffix,
                contestId
        );
        Long problemId = jdbcTemplate.queryForObject(
                "SELECT id FROM problem WHERE name = ?",
                Long.class,
                "scoreboard-pipeline-problem-" + suffix
        );

        List<Object[]> users = new ArrayList<>(userCount);
        for (int index = 0; index < userCount; index++) {
            users.add(new Object[]{"scoreboard-pipeline-user-" + suffix + "-" + index, "pass"});
        }
        jdbcTemplate.batchUpdate("INSERT INTO `user` (name, pass) VALUES (?, ?)", users);
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM `user` WHERE name LIKE ? ORDER BY id",
                Long.class,
                "scoreboard-pipeline-user-" + suffix + "-%"
        );
        assertThat(userIds).hasSize(userCount);

        List<Object[]> submissions = new ArrayList<>(eventCount);
        List<Object[]> outboxes = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            long submissionId = 910_000_000_000_000_000L + index;
            long userId = userIds.get(index % userIds.size());
            LocalDateTime submittedAt = contestStart.plusSeconds(index % 3_600);
            submissions.add(new Object[]{
                    submissionId,
                    contestId,
                    problemId,
                    userId,
                    submittedAt,
                    "code-" + index,
                    String.format(Locale.ROOT, "%064x", index)
            });
            outboxes.add(new Object[]{
                    submissionId,
                    contestId,
                    problemId,
                    userId,
                    contestStart,
                    submittedAt,
                    submittedAt.plusSeconds(1),
                    index < userCount ? "WRONG_ANSWER" : "ACCEPTED",
                    submittedAt.plusSeconds(1)
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission (
                    id, contest_id, problem_id, user_id, submitted_time, code, code_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, submissions);
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission_outbox (
                    contest_submission_id, contest_id, problem_id, user_id,
                    contest_start, submitted_time, judged_at, result, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, outboxes);
    }

    private void flushRedis() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
