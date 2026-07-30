package my.oj.web.contest.scoreboard.rebuild;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.outbox.worker.ContestScoreboardOutboxProcessor;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criterion for making scoreboard updates order-independent: the live outbox
 * path and a rebuild from the stored contest results must produce the same scoreboard.
 *
 * <p>The live path drains {@code contest_submission_outbox} in judging order and applies each
 * event with the Lua script; the rebuild replays every result in submission order through the
 * Java store. The seeded data deliberately judges a late wrong attempt after an earlier
 * accepted one, which is what used to make the two paths disagree.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.outbox.scheduler.enabled=false",
        "rank.streak.batch.enabled=false"
})
@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class ContestScoreboardLiveVersusRebuildRedisIntegrationTests {

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
    private ContestScoreboardRebuildService rebuildService;

    @Autowired
    private ContestScoreboardService scoreboardService;

    private Long contestId;

    @AfterEach
    void tearDown() {
        if (contestId == null) {
            return;
        }
        scoreboardService.reset(contestId);
        List<Long> userIds = contestUserIds();
        jdbcTemplate.update("DELETE FROM contest_submission_outbox WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest_submission_result WHERE contest_id = ?", contestId);
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
    void liveOutboxScoreboardMatchesARebuildFromContestResults() {
        flushRedis();
        List<Attempt> attempts = seedContest();

        drainOutbox();
        List<ContestScoreboardEntry> live = scoreboardService.currentRanking(contestId);

        rebuildService.rebuildFromContestResults(contestId);
        List<ContestScoreboardEntry> rebuilt = scoreboardService.currentRanking(contestId);

        assertThat(live).as("seeded attempts: %s", attempts).isEqualTo(expectedRanking());
        assertThat(rebuilt).isEqualTo(live);
    }

    private void drainOutbox() {
        while (processor.processBatch(100, Duration.ofSeconds(30)).claimed() > 0) {
            // keep draining until the outbox is empty
        }
        Long pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_outbox WHERE status <> 'COMPLETED'",
                Long.class
        );
        assertThat(pending).as("every scoreboard outbox row is applied").isZero();
        // Guards against a row being completed by something other than this drain — a
        // scheduler left running in another cached Spring context, for instance.
        assertThat(redisTemplate.opsForSet().size("contest:scoreboard:" + contestId + ":processed"))
                .as("every completed event reached this Redis scoreboard")
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM contest_submission_outbox WHERE contest_id = ?",
                        Long.class,
                        contestId
                ));
    }

    /**
     * Two users, two problems each. User 1 gets the regression case: the accepted attempt at
     * minute 12 is judged before the wrong attempt at minute 10.
     */
    private List<Attempt> seedContest() {
        String suffix = UUID.randomUUID().toString();
        LocalDateTime contestStart = LocalDateTime.of(2026, 3, 10, 12, 0);
        jdbcTemplate.update(
                "INSERT INTO contest (name, start_time, end_time) VALUES (?, ?, ?)",
                "live-vs-rebuild-" + suffix,
                contestStart,
                contestStart.plusHours(2)
        );
        contestId = jdbcTemplate.queryForObject(
                "SELECT id FROM contest WHERE name = ?",
                Long.class,
                "live-vs-rebuild-" + suffix
        );
        long problemA = insertProblem("live-vs-rebuild-a-" + suffix, 1);
        long problemB = insertProblem("live-vs-rebuild-b-" + suffix, 2);
        long userA = insertUser("live-vs-rebuild-user-a-" + suffix);
        long userB = insertUser("live-vs-rebuild-user-b-" + suffix);

        // (submissionId, problem, user, submitted minute, judged minute, result)
        List<Attempt> attempts = List.of(
                new Attempt(920_000_000_000_000_001L, problemA, userA, 10, 14, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_002L, problemA, userA, 12, 12, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_003L, problemB, userA, 20, 21, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_004L, problemB, userA, 22, 30, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_005L, problemB, userA, 25, 26, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_006L, problemA, userB, 5, 40, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_007L, problemA, userB, 7, 8, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_008L, problemB, userB, 9, 10, SubmissionResult.RUNTIME_ERROR)
        );

        List<Object[]> submissions = new ArrayList<>();
        List<Object[]> results = new ArrayList<>();
        List<Object[]> outboxes = new ArrayList<>();
        int hash = 0;
        for (Attempt attempt : attempts) {
            LocalDateTime submittedAt = contestStart.plusMinutes(attempt.submittedMinute());
            LocalDateTime judgedAt = contestStart.plusMinutes(attempt.judgedMinute());
            submissions.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.problemId(), attempt.userId(),
                    submittedAt, "code", String.format(Locale.ROOT, "%064x", hash++)
            });
            results.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.result().name(), judgedAt,
                    attempt.result().name(), judgedAt
            });
            outboxes.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.problemId(), attempt.userId(),
                    contestStart, submittedAt, judgedAt, attempt.result().name(), judgedAt
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission (
                    id, contest_id, problem_id, user_id, submitted_time, code, code_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, submissions);
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission_result (
                    submission_id, contest_id, provisional_result, provisional_judged_at,
                    final_result, final_judged_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, results);
        // created_at follows the judging clock, so the outbox is drained in judging order.
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission_outbox (
                    contest_submission_id, contest_id, problem_id, user_id,
                    contest_start, submitted_time, judged_at, result, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, outboxes);
        return attempts;
    }

    /**
     * User A: problem A solved at minute 12 with one earlier wrong attempt (12 + 5), problem B
     * solved at minute 22 with one earlier wrong attempt (22 + 5). User B: problem A solved at
     * minute 5 with no earlier wrong attempt, problem B never solved.
     */
    private List<ContestScoreboardEntry> expectedRanking() {
        List<Long> userIds = contestUserIds();
        return List.of(
                new ContestScoreboardEntry(userIds.get(0), 2, 44L),
                new ContestScoreboardEntry(userIds.get(1), 1, 5L)
        );
    }

    private List<Long> contestUserIds() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM contest_submission WHERE contest_id = ? ORDER BY user_id",
                Long.class,
                contestId
        );
    }

    private long insertProblem(String name, int contestNum) {
        jdbcTemplate.update(
                "INSERT INTO problem (name, contest_id, contest_num) VALUES (?, ?, ?)",
                name,
                contestId,
                contestNum
        );
        return jdbcTemplate.queryForObject("SELECT id FROM problem WHERE name = ?", Long.class, name);
    }

    private long insertUser(String name) {
        jdbcTemplate.update("INSERT INTO `user` (name, pass) VALUES (?, ?)", name, "pass");
        return jdbcTemplate.queryForObject("SELECT id FROM `user` WHERE name = ?", Long.class, name);
    }

    private void flushRedis() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private record Attempt(long submissionId,
                           long problemId,
                           long userId,
                           int submittedMinute,
                           int judgedMinute,
                           SubmissionResult result) {
    }
}
