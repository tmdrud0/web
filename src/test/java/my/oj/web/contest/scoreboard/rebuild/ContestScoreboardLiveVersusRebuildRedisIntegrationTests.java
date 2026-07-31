package my.oj.web.contest.scoreboard.rebuild;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.outbox.worker.ContestScoreboardOutboxProcessor;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criterion for making scoreboard updates order-independent: the live outbox
 * path and a rebuild from the stored contest results must produce the same scoreboard.
 *
 * <p>The live path drains {@code contest_submission_outbox} in judging order and applies each
 * event with the Lua script; the rebuild replays every result in submission order through the
 * scoreboard applier. The seeded data deliberately judges a late wrong attempt after an earlier
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
    private ContestScoreboardRebuildService rebuildService;

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
    void liveOutboxScoreboardMatchesARebuildFromContestResults() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        seedContest();

        drainOutbox();
        List<ContestScoreboardEntry> live = scoreboardService.currentRanking(contest.contestId());

        rebuildService.rebuildFromContestResults(contest.contestId());
        List<ContestScoreboardEntry> rebuilt = scoreboardService.currentRanking(contest.contestId());

        assertThat(live).isEqualTo(expectedRanking());
        assertThat(rebuilt).isEqualTo(live);
    }

    private void drainOutbox() {
        while (processor.processBatch(100, Duration.ofSeconds(30)).claimed() > 0) {
            // keep draining until the outbox is empty
        }
        assertThat(ContestScoreboardTestData.unappliedEvents(jdbcTemplate, contest.contestId()))
                .as("every scoreboard outbox row is applied")
                .isZero();
        // Guards against a row being completed by something other than this drain — a
        // scheduler left running in another cached Spring context, for instance.
        assertThat(redisTemplate.opsForSet().size(ContestScoreboardTestData.processedKey(contest.contestId())))
                .as("every completed event reached this Redis scoreboard")
                .isEqualTo(ContestScoreboardTestData.seededEvents(jdbcTemplate, contest.contestId()));
    }

    /**
     * Two users, two problems each. User 1 gets the regression case: the accepted attempt at
     * minute 12 is judged before the wrong attempt at minute 10.
     */
    private void seedContest() {
        contest = ContestScoreboardTestData.seedContest(jdbcTemplate, "live-vs-rebuild", CONTEST_START, 2, 2);
        long problemA = contest.problemIds().get(0);
        long problemB = contest.problemIds().get(1);
        long userA = contest.userIds().get(0);
        long userB = contest.userIds().get(1);

        ContestScoreboardTestData.insertAttempts(jdbcTemplate, contest.contestId(), CONTEST_START, List.of(
                new Attempt(920_000_000_000_000_001L, problemA, userA, 10, 14, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_002L, problemA, userA, 12, 12, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_003L, problemB, userA, 20, 21, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_004L, problemB, userA, 22, 30, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_005L, problemB, userA, 25, 26, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_006L, problemA, userB, 5, 40, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_007L, problemA, userB, 7, 8, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_008L, problemB, userB, 9, 10, SubmissionResult.RUNTIME_ERROR)
        ), true);
    }

    /**
     * User A: problem A solved at minute 12 with one earlier wrong attempt (12 + 5), problem B
     * solved at minute 22 with one earlier wrong attempt (22 + 5). User B: problem A solved at
     * minute 5 with no earlier wrong attempt, problem B never solved.
     */
    private List<ContestScoreboardEntry> expectedRanking() {
        return List.of(
                new ContestScoreboardEntry(contest.userIds().get(0), 2, 44L),
                new ContestScoreboardEntry(contest.userIds().get(1), 1, 5L)
        );
    }
}
