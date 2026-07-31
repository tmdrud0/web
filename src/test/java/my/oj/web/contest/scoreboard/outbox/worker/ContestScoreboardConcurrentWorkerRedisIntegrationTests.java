package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.testsupport.ContestScoreboardTestData;
import my.oj.web.testsupport.ContestScoreboardTestData.Attempt;
import my.oj.web.testsupport.ContestScoreboardTestData.SeededContest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 3, 10, 12, 0);

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

    @Autowired
    private ContestScoreboardApplier scoreboardApplier;

    private SeededContest contest;

    @AfterEach
    void tearDown() {
        if (contest == null) {
            return;
        }
        scoreboardApplier.reset(contest.contestId());
        ContestScoreboardTestData.deleteContest(jdbcTemplate, contest.contestId());
        contest = null;
        ContestScoreboardTestData.flushRedis(redisTemplate);
    }

    @Test
    void fourWorkersDrainingTogetherReachTheSameScoreboardAsOne() throws Exception {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        seedContest();

        drainWith(1);
        List<ContestScoreboardEntry> singleWorkerRanking = scoreboardService.currentRanking(contest.contestId());

        replayFromScratch();
        drainWith(WORKER_COUNT);
        List<ContestScoreboardEntry> concurrentRanking = scoreboardService.currentRanking(contest.contestId());

        assertThat(singleWorkerRanking).isNotEmpty();
        assertThat(concurrentRanking).isEqualTo(singleWorkerRanking);
    }

    /** Clears the applied scoreboard and puts every event back on the queue. */
    private void replayFromScratch() {
        scoreboardApplier.reset(contest.contestId());
        jdbcTemplate.update("""
                UPDATE contest_submission_outbox
                SET status = 'PENDING', redis_seq = NULL, processed_at = NULL, claim_token = NULL,
                    claimed_at = NULL, next_attempt_at = NULL, last_error_message = NULL,
                    due_at = created_at
                WHERE contest_id = ?
                """, contest.contestId());
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
        // Guards against a row being completed by something other than these workers — a
        // scheduler left running in another cached Spring context, for instance.
        assertThat(redisTemplate.opsForSet().size(ContestScoreboardTestData.processedKey(contest.contestId())))
                .as("every completed event reached this Redis scoreboard")
                .isEqualTo(ContestScoreboardTestData.seededEvents(jdbcTemplate, contest.contestId()));
    }

    private long unappliedEvents() {
        return ContestScoreboardTestData.unappliedEvents(jdbcTemplate, contest.contestId());
    }

    /**
     * Three users on three problems with wrong attempts around each accepted one, judged in an
     * order that differs from the submission order.
     */
    private void seedContest() {
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "scoreboard-concurrent-worker", CONTEST_START, 3, 3);

        List<Attempt> attempts = new ArrayList<>();
        long submissionId = 940_000_000_000_000_000L;
        int sequence = 0;
        for (Long userId : contest.userIds()) {
            for (Long problemId : contest.problemIds()) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    int submittedMinute = 5 + attempt * 4 + sequence % 7;
                    // The accepted attempt is judged first even though it was submitted last.
                    int judgedMinute = attempt == 2 ? submittedMinute : submittedMinute + 6;
                    attempts.add(new Attempt(
                            submissionId++,
                            problemId,
                            userId,
                            submittedMinute,
                            judgedMinute,
                            attempt == 2 ? SubmissionResult.ACCEPTED : SubmissionResult.WRONG_ANSWER
                    ));
                    sequence++;
                }
            }
        }
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, attempts, false);
    }
}
