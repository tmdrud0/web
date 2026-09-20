package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.rebuild.ContestScoreboardRebuildService;
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

/**
 * The full-replay mode against a real Redis and MySQL.
 *
 * <p>The recovery mode is left at its default here, because the service is registered in every
 * mode: only the startup runner and the retention-gap fallback are mode-conditional, so calling the
 * service directly is the same code path the mode runs.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.scoreboard.stream.consumer.enabled=false",
        "rank.streak.batch.enabled=false"
})
@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class ContestScoreboardFullReplayRedisIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 5, 4, 12, 0);

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
    private ContestScoreboardFullReplayService fullReplayService;
    @Autowired
    private ContestScoreboardRebuildService rebuildService;
    @Autowired
    private ContestScoreboardService scoreboardService;
    @Autowired
    private ContestScoreboardApplier scoreboardApplier;

    private SeededContest contest;

    @AfterEach
    void tearDown() {
        if (contest != null) {
            scoreboardApplier.reset(contest.contestId());
            ContestScoreboardTestData.deleteContest(jdbcTemplate, contest.contestId());
            contest = null;
        }
        ContestScoreboardTestData.flushRedis(redisTemplate);
    }

    /**
     * A replay leaves the restored scoreboard in place, and the checkpoint with it.
     *
     * <p>The fixture holds one submission Redis has already scored but MySQL has no result row for -
     * the window between the two writes that the {@code db-pending} set exists for. A replay must not
     * lose it: resetting the contest is exactly what erases it, which the rebuild call at the end
     * demonstrates so the assertion cannot pass for a reason other than "no reset happened".</p>
     */
    @Test
    void replayKeepsTheScoreboardStateTheSnapshotWasRestoredWith() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        seedContest(2, 3);
        long unpersistedUser = contest.userIds().get(2);
        Attempt unpersisted = new Attempt(
                920_000_000_000_000_009L,
                contest.problemIds().get(0),
                unpersistedUser,
                3,
                4,
                SubmissionResult.ACCEPTED
        );
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, List.of(unpersisted), false);

        // The restored scoreboard: every event reached Redis, but MySQL only ever stored eight of
        // them - the window between the two writes that the db-pending set exists for.
        List<Attempt> judgingOrder = judgingOrder();
        for (int offset = 0; offset < judgingOrder.size(); offset++) {
            scoreboardApplier.apply(ContestScoreboardApplier.ApplyRequest.stream(
                    offset, update(judgingOrder.get(offset))));
        }
        scoreboardApplier.apply(ContestScoreboardApplier.ApplyRequest.stream(
                judgingOrder.size(), update(unpersisted)));
        List<ContestScoreboardEntry> scoredBeforeReplay = scoreboardService.currentRanking(contest.contestId());
        assertThat(scoredBeforeReplay).extracting(ContestScoreboardEntry::userId).contains(unpersistedUser);
        long offsetBeforeReplay = scoreboardApplier.currentStreamOffset();

        int replayed = fullReplayService.replayContest(contest.contestId());

        assertThat(replayed).isEqualTo(attempts().size());
        assertThat(scoreboardService.currentRanking(contest.contestId()))
                .as("a replay must not clear the scoreboard Redis was restored with")
                .isEqualTo(scoredBeforeReplay);
        assertThat(scoreboardApplier.currentStreamOffset())
                .as("a rebuild request carries no offset, so a replay cannot move the checkpoint")
                .isEqualTo(offsetBeforeReplay);

        rebuildService.rebuildFromContestResults(contest.contestId());

        assertThat(scoreboardService.currentRanking(contest.contestId()))
                .as("the rebuild this mode replaces does reset, which is what the assertion above rules out")
                .extracting(ContestScoreboardEntry::userId)
                .doesNotContain(unpersistedUser);
    }

    /** Re-sending the same judgements must not score them twice. */
    @Test
    void replayingTwiceLeavesTheSameScoreboard() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        seedContest(2, 2);

        assertThat(fullReplayService.replayContest(contest.contestId())).isEqualTo(attempts().size());
        List<ContestScoreboardEntry> afterFirst = scoreboardService.currentRanking(contest.contestId());
        assertThat(afterFirst).isEqualTo(expectedRanking());

        assertThat(fullReplayService.replayContest(contest.contestId())).isEqualTo(attempts().size());

        assertThat(scoreboardService.currentRanking(contest.contestId())).isEqualTo(afterFirst);
        assertThat(redisTemplate.opsForSet().size(ContestScoreboardTestData.processedKey(contest.contestId())))
                .as("the processed set grows once per submission, not once per replay")
                .isEqualTo(attempts().size());
    }

    /** The point of the mode: results the restored snapshot never saw get re-applied. */
    @Test
    void replayAppliesTheJudgementsTheRestoredSnapshotNeverSaw() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        seedContest(2, 2);
        List<Attempt> judgingOrder = judgingOrder();
        for (int offset = 0; offset < 3; offset++) {
            scoreboardApplier.apply(ContestScoreboardApplier.ApplyRequest.stream(
                    offset, update(judgingOrder.get(offset))));
        }
        assertThat(scoreboardApplier.currentStreamOffset()).isEqualTo(2L);
        assertThat(scoreboardService.currentRanking(contest.contestId())).isNotEqualTo(expectedRanking());

        fullReplayService.replayContest(contest.contestId());

        assertThat(scoreboardService.currentRanking(contest.contestId())).isEqualTo(expectedRanking());
        assertThat(scoreboardApplier.currentStreamOffset())
                .as("filling the scoreboard in must not advance the broker checkpoint")
                .isEqualTo(2L);
    }

    /**
     * While a contest runs MySQL holds a provisional result and no final one, so a replay has to
     * read the provisional result. Reading {@code final_result} alone would apply nothing here,
     * which is the shape most contests are in when a replay runs.
     */
    @Test
    void replayAppliesTheProvisionalResultsOfARunningContest() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "full-replay-provisional", CONTEST_START, 2, 2);
        ContestScoreboardTestData.insertAttemptsWithProvisionalResultOnly(
                jdbcTemplate, contest.contestId(), CONTEST_START, attempts());

        assertThat(fullReplayService.replayContest(contest.contestId())).isEqualTo(attempts().size());

        assertThat(scoreboardService.currentRanking(contest.contestId())).isEqualTo(expectedRanking());
    }

    /**
     * An unjudged row is not a result, and replaying one would mark the submission as handled - the
     * script adds to its processed set outside the branch that skips PENDING - so the real judgement
     * that arrives later would be ignored for good.
     */
    @Test
    void unjudgedSubmissionsAreLeftForTheirRealJudgement() {
        ContestScoreboardTestData.flushRedis(redisTemplate);
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "full-replay-unjudged", CONTEST_START, 1, 2);
        long judgedUser = contest.userIds().get(0);
        long unjudgedUser = contest.userIds().get(1);
        long problemId = contest.problemIds().get(0);
        Attempt judged = new Attempt(
                920_000_000_000_000_101L, problemId, judgedUser, 10, 10, SubmissionResult.ACCEPTED);
        Attempt unjudged = new Attempt(
                920_000_000_000_000_102L, problemId, unjudgedUser, 5, 5, SubmissionResult.PENDING);
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, List.of(judged, unjudged), true);

        fullReplayService.replayContest(contest.contestId());

        assertThat(redisTemplate.opsForSet()
                .isMember(ContestScoreboardTestData.processedKey(contest.contestId()),
                        Long.toString(unjudged.submissionId())))
                .as("an unjudged submission must not be recorded as processed")
                .isFalse();
        assertThat(scoreboardService.currentRanking(contest.contestId()))
                .extracting(ContestScoreboardEntry::userId)
                .containsExactly(judgedUser);

        // The judgement the replay left room for still reaches the scoreboard.
        Attempt nowJudged = new Attempt(
                unjudged.submissionId(), problemId, unjudgedUser, 5, 5, SubmissionResult.ACCEPTED);
        scoreboardApplier.apply(ContestScoreboardApplier.ApplyRequest.stream(0L, update(nowJudged)));

        assertThat(scoreboardService.currentRanking(contest.contestId()))
                .as("the real judgement must not be swallowed by an earlier replay")
                .extracting(ContestScoreboardEntry::userId)
                .contains(judgedUser, unjudgedUser);
        assertThat(scoreboardService.currentRanking(contest.contestId()))
                .contains(new ContestScoreboardEntry(unjudgedUser, 1, 5L));
    }

    private void seedContest(int problemCount, int userCount) {
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "full-replay", CONTEST_START, problemCount, userCount);
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, attempts(), true);
    }

    /**
     * The same fixture the live-versus-rebuild test uses, so the expected ranking below is the one
     * already established for these judgements.
     */
    private List<Attempt> attempts() {
        return ContestScoreboardTestData.standardAttempts(contest, CONTEST_START);
    }

    private List<Attempt> judgingOrder() {
        return ContestScoreboardTestData.inJudgingOrder(attempts());
    }

    private List<ContestScoreboardEntry> expectedRanking() {
        return List.of(
                new ContestScoreboardEntry(contest.userIds().get(0), 2, 44L),
                new ContestScoreboardEntry(contest.userIds().get(1), 1, 5L)
        );
    }

    private ContestScoreboardUpdate update(Attempt attempt) {
        return new ContestScoreboardUpdate(
                attempt.submissionId(),
                contest.contestId(),
                attempt.problemId(),
                attempt.userId(),
                CONTEST_START,
                CONTEST_START.plusMinutes(attempt.submittedMinute()),
                attempt.result(),
                CONTEST_START.plusMinutes(attempt.judgedMinute())
        );
    }
}
