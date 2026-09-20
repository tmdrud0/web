package my.oj.web.contest.scoreboard.rebuild;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The live stream and a DB rebuild must share the same commutative scoring result. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.scoreboard.stream.consumer.enabled=false",
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
    private ContestScoreboardRebuildService rebuildService;
    @Autowired
    private ContestScoreboardService scoreboardService;
    @Autowired
    private ContestScoreboardApplier scoreboardApplier;

    private SeededContest contest;
    private List<Attempt> attempts;

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
    void liveStreamScoreboardMatchesARebuildFromContestResults() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        seedContest();

        applyLiveStream();
        List<ContestScoreboardEntry> live = scoreboardService.currentRanking(contest.contestId());

        rebuildService.rebuildFromContestResults(contest.contestId());
        List<ContestScoreboardEntry> rebuilt = scoreboardService.currentRanking(contest.contestId());

        assertThat(live).isEqualTo(expectedRanking());
        assertThat(rebuilt).isEqualTo(live);
    }

    private void applyLiveStream() {
        List<Attempt> judgingOrder = ContestScoreboardTestData.inJudgingOrder(attempts);
        for (int index = 0; index < judgingOrder.size(); index++) {
            Attempt attempt = judgingOrder.get(index);
            scoreboardApplier.apply(ContestScoreboardApplier.ApplyRequest.stream(
                    index,
                    new ContestScoreboardUpdate(
                            attempt.submissionId(),
                            contest.contestId(),
                            attempt.problemId(),
                            attempt.userId(),
                            CONTEST_START,
                            CONTEST_START.plusMinutes(attempt.submittedMinute()),
                            attempt.result(),
                            CONTEST_START.plusMinutes(attempt.judgedMinute())
                    )
            ));
        }
        assertThat(scoreboardApplier.currentStreamOffset()).isEqualTo(judgingOrder.size() - 1L);
        assertThat(redisTemplate.opsForSet().size(ContestScoreboardTestData.processedKey(contest.contestId())))
                .as("every stream event reached this Redis scoreboard")
                .isEqualTo(judgingOrder.size());
    }

    private void seedContest() {
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "live-vs-rebuild", CONTEST_START, 2, 2);
        attempts = ContestScoreboardTestData.standardAttempts(contest, CONTEST_START);
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, attempts, true);
    }

    private List<ContestScoreboardEntry> expectedRanking() {
        return List.of(
                new ContestScoreboardEntry(contest.userIds().get(0), 2, 44L),
                new ContestScoreboardEntry(contest.userIds().get(1), 1, 5L)
        );
    }
}
