package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoreboard outbox worker no longer holds a MySQL named lock, so several instances drain
 * the queue at the same time. Claiming hands each row to one worker only, and applying a
 * judgement is order-independent, so a concurrent drain has to end on the same scoreboard as a
 * single worker draining the same events.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.outbox.scheduler.enabled=false",
        "rank.streak.batch.enabled=false"
})
@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class ContestScoreboardConcurrentWorkerRedisIntegrationTests {

    private static final int WORKER_COUNT = 4;
    private static final int BATCH_SIZE = 10;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.getInteger("redisPort", 16379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ContestScoreboardOutboxProcessor processor;

    @Autowired
    private ContestScoreboardService scoreboardService;

    private Long contestId;

    @AfterEach
    void tearDown() {
        if (contestId == null) {
            return;
        }
        scoreboardService.reset(contestId);
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM contest_submission WHERE contest_id = ?",
                Long.class,
                contestId
        );
        jdbcTemplate.update("DELETE FROM contest_submission_outbox WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest_submission WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM problem WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest WHERE id = ?", contestId);
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
        contestId = null;
        flushRedis();
    }

    @Test
    void fourWorkersDrainingTogetherReachTheSameScoreboardAsOne() throws Exception {
        flushRedis();
        seedContest();

        drainWith(1);
        List<ContestScoreboardEntry> singleWorkerRanking = scoreboardService.currentRanking(contestId);

        replayFromScratch();
        drainWith(WORKER_COUNT);
        List<ContestScoreboardEntry> concurrentRanking = scoreboardService.currentRanking(contestId);

        assertThat(singleWorkerRanking).isNotEmpty();
        assertThat(concurrentRanking).isEqualTo(singleWorkerRanking);
    }

    /** Clears the applied scoreboard and puts every event back on the queue. */
    private void replayFromScratch() {
        scoreboardService.reset(contestId);
        jdbcTemplate.update("""
                UPDATE contest_submission_outbox
                SET status = 'PENDING', redis_seq = NULL, processed_at = NULL, claim_token = NULL,
                    claimed_at = NULL, next_attempt_at = NULL, last_error_message = NULL
                WHERE contest_id = ?
                """, contestId);
    }

    private void drainWith(int workerCount) throws Exception {
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        try {
            List<Future<?>> workers = new ArrayList<>(workerCount);
            for (int worker = 0; worker < workerCount; worker++) {
                workers.add(executor.submit(() -> {
                    startTogether.await(5, TimeUnit.SECONDS);
                    while (System.nanoTime() < deadline && unappliedEvents() > 0) {
                        processor.processBatch(BATCH_SIZE, CLAIM_LEASE);
                    }
                    return null;
                }));
            }
            startTogether.countDown();
            for (Future<?> worker : workers) {
                worker.get(DRAIN_TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(unappliedEvents()).as("every scoreboard outbox row is applied").isZero();
    }

    private long unappliedEvents() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM contest_submission_outbox
                WHERE contest_id = ? AND status <> 'COMPLETED'
                """, Long.class, contestId);
        return count == null ? 0L : count;
    }

    /**
     * Three users on three problems with wrong attempts around each accepted one, judged in an
     * order that differs from the submission order.
     */
    private void seedContest() {
        String suffix = UUID.randomUUID().toString();
        String name = "scoreboard-concurrent-worker-" + suffix;
        LocalDateTime contestStart = LocalDateTime.of(2026, 3, 10, 12, 0);
        jdbcTemplate.update(
                "INSERT INTO contest (name, start_time, end_time) VALUES (?, ?, ?)",
                name,
                contestStart,
                contestStart.plusHours(3)
        );
        contestId = jdbcTemplate.queryForObject("SELECT id FROM contest WHERE name = ?", Long.class, name);

        List<Long> problemIds = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            jdbcTemplate.update(
                    "INSERT INTO problem (name, contest_id, contest_num) VALUES (?, ?, ?)",
                    name + "-problem-" + index,
                    contestId,
                    index
            );
            problemIds.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM problem WHERE name = ?",
                    Long.class,
                    name + "-problem-" + index
            ));
        }
        List<Long> userIds = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            jdbcTemplate.update("INSERT INTO `user` (name, pass) VALUES (?, ?)", name + "-user-" + index, "pass");
            userIds.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM `user` WHERE name = ?",
                    Long.class,
                    name + "-user-" + index
            ));
        }

        List<Object[]> submissions = new ArrayList<>();
        List<Object[]> outboxes = new ArrayList<>();
        long submissionId = 940_000_000_000_000_000L;
        int sequence = 0;
        for (Long userId : userIds) {
            for (Long problemId : problemIds) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    int submittedMinute = 5 + attempt * 4 + sequence % 7;
                    // The accepted attempt is judged first even though it was submitted last.
                    int judgedMinute = attempt == 2 ? submittedMinute : submittedMinute + 6;
                    SubmissionResult result = attempt == 2
                            ? SubmissionResult.ACCEPTED
                            : SubmissionResult.WRONG_ANSWER;
                    LocalDateTime submittedAt = contestStart.plusMinutes(submittedMinute);
                    LocalDateTime judgedAt = contestStart.plusMinutes(judgedMinute);
                    submissions.add(new Object[]{
                            submissionId, contestId, problemId, userId, submittedAt,
                            "code", String.format(Locale.ROOT, "%064x", submissionId)
                    });
                    outboxes.add(new Object[]{
                            submissionId, contestId, problemId, userId, contestStart, submittedAt,
                            judgedAt, result.name(), judgedAt
                    });
                    submissionId++;
                    sequence++;
                }
            }
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
