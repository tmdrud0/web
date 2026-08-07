package my.oj.web.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.problem.Problem;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queries are MySQL-specific - a LIMIT inside a derived table, TIMESTAMPDIFF against
 * CURRENT_TIMESTAMP(6), an ORDER BY chosen to match a particular index - so the unit tests, which
 * stub the JdbcTemplate, cannot say whether any of it runs. This says whether it runs and returns
 * the right numbers.
 *
 * <p>Everything is asserted as a delta against a first poll. The suite shares one database and
 * not every test rolls back, so absolute counts would depend on execution order.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestQuerydslConfig.class)
class ContestOutboxBacklogMetricsMySqlIntegrationTests {

    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 3, 10, 12, 1);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SimpleMeterRegistry registry;
    private Contest contest;
    private User user;
    private Problem problem;

    @BeforeEach
    void seedContest() {
        registry = new SimpleMeterRegistry();
        String suffix = UUID.randomUUID().toString();
        user = User.create("outbox-backlog-" + suffix, "pass");
        contest = new Contest("outbox-backlog-" + suffix);
        entityManager.persist(user);
        entityManager.persist(contest);
        problem = Problem.create("outbox-backlog-" + suffix, contest, 1L);
        entityManager.persist(problem);
        entityManager.flush();
    }

    @Test
    void countsWaitingRowsInBothOutboxes() {
        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();
        double judgePendingBefore = backlog("judge", "PENDING");
        double judgePublishingBefore = backlog("judge", "PUBLISHING");
        double scoreboardPendingBefore = backlog("scoreboard", "PENDING");
        double scoreboardFailedBefore = backlog("scoreboard", "FAILED");

        insertJudgeOutbox("PENDING", 0);
        insertJudgeOutbox("PENDING", 0);
        insertJudgeOutbox("PUBLISHING", 0);
        insertScoreboardOutbox("PENDING", 0);
        insertScoreboardOutbox("FAILED", 60);

        metrics.poll();

        assertThat(backlog("judge", "PENDING") - judgePendingBefore).isEqualTo(2.0);
        assertThat(backlog("judge", "PUBLISHING") - judgePublishingBefore).isEqualTo(1.0);
        assertThat(backlog("scoreboard", "PENDING") - scoreboardPendingBefore).isEqualTo(1.0);
        assertThat(backlog("scoreboard", "FAILED") - scoreboardFailedBefore).isEqualTo(1.0);
    }

    /**
     * A terminal row is not backlog, and counting it would tie the query cost to table size:
     * neither outbox purges terminal rows (pipeline history 9.4), so that count only ever grows.
     */
    @Test
    void ignoresRowsThatHaveAlreadyLeftTheOutbox() {
        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();
        double judgeBefore = backlog("judge", "PENDING") + backlog("judge", "PUBLISHING");
        double scoreboardBefore = backlog("scoreboard", "PENDING")
                + backlog("scoreboard", "PROCESSING")
                + backlog("scoreboard", "FAILED");

        insertJudgeOutbox("PUBLISHED", 0);
        insertScoreboardOutbox("COMPLETED", null);

        metrics.poll();

        assertThat(backlog("judge", "PENDING") + backlog("judge", "PUBLISHING")).isEqualTo(judgeBefore);
        assertThat(backlog("scoreboard", "PENDING")
                + backlog("scoreboard", "PROCESSING")
                + backlog("scoreboard", "FAILED")).isEqualTo(scoreboardBefore);
    }

    /**
     * The LIMIT lives in a derived table, where the optimizer is free to merge it into the outer
     * query and drop it. If that happened the scan would grow with the backlog it is measuring,
     * so this pins the behaviour rather than trusting it.
     */
    @Test
    void stopsCountingAtTheConfiguredCap() {
        for (int i = 0; i < 5; i++) {
            insertJudgeOutbox("PENDING", 0);
            insertScoreboardOutbox("PENDING", 0);
        }

        ContestOutboxBacklogMetrics capped = metrics(2);
        capped.poll();

        assertThat(backlog("judge", "PENDING") + backlog("judge", "PUBLISHING")).isEqualTo(2.0);
        assertThat(backlog("scoreboard", "PENDING")
                + backlog("scoreboard", "PROCESSING")
                + backlog("scoreboard", "FAILED")).isEqualTo(2.0);
    }

    /**
     * Both ends of the subtraction are MySQL's own clock, so the age holds even when the JVM's
     * clock does not agree with the database's.
     */
    @Test
    void measuresHeadOfLineAgeOnTheDatabaseClock() {
        insertJudgeOutbox("PENDING", -90);
        insertScoreboardOutbox("PENDING", -90);

        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();

        assertThat(headLag("judge")).isGreaterThanOrEqualTo(90.0);
        assertThat(headLag("scoreboard")).isGreaterThanOrEqualTo(90.0);
    }

    /**
     * The split the head-lag gauge exists to keep: a row in exponential backoff is legitimately
     * old, and reporting that age as backlog would say "throughput is short" about a row that is
     * waiting on purpose. Its FAILED count is the signal for it instead.
     *
     * <p>Asserted with the backoff already elapsed, which is the case an "overdue by due_at"
     * query would have picked up. A future due_at proves nothing here - it would read zero under
     * either definition.
     */
    @Test
    void leavesARetryingRowOutOfTheBacklogAge() {
        insertScoreboardOutbox("FAILED", -3_600);
        insertScoreboardOutbox("PROCESSING", -3_600);

        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();

        assertThat(backlog("scoreboard", "FAILED")).isGreaterThanOrEqualTo(1.0);
        assertThat(backlog("scoreboard", "PROCESSING")).isGreaterThanOrEqualTo(1.0);
        assertThat(headLag("scoreboard"))
                .as("an hour-old FAILED row is a stuck row, not a deep queue")
                .isZero();
    }

    /** The same row set, plus one PENDING row, which is the one the age is meant to see. */
    @Test
    void reportsTheOldestPendingRowPastTheRetryingOnes() {
        insertScoreboardOutbox("FAILED", -3_600);
        insertScoreboardOutbox("PROCESSING", -3_600);
        insertScoreboardOutbox("PENDING", -45);

        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();

        assertThat(headLag("scoreboard")).isBetween(45.0, 3_000.0);
    }

    private ContestOutboxBacklogMetrics metrics(int maxCountedRows) {
        ContestOutboxBacklogMetrics metrics = new ContestOutboxBacklogMetrics(
                jdbcTemplate, new ContestOutboxMetricsProperties(maxCountedRows));
        metrics.bindTo(registry);
        return metrics;
    }

    private double backlog(String outbox, String status) {
        return registry.get("contest.outbox.backlog")
                .tags("outbox", outbox, "status", status)
                .gauge().value();
    }

    private double headLag(String outbox) {
        return registry.get("contest.outbox.head.lag").tag("outbox", outbox).gauge().value();
    }

    /** @param createdSecondsFromNow negative to age the row, on MySQL's clock rather than the JVM's */
    private void insertJudgeOutbox(String status, int createdSecondsFromNow) {
        jdbcTemplate.update("""
                        INSERT INTO contest_judge_outbox (submission_id, status, created_at)
                        VALUES (?, ?, TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)))
                        """,
                persistSubmission(), status, createdSecondsFromNow);
    }

    /** @param dueSecondsFromNow null for a terminal row, which never becomes claimable again */
    private void insertScoreboardOutbox(String status, Integer dueSecondsFromNow) {
        jdbcTemplate.update("""
                        INSERT INTO contest_submission_outbox
                            (contest_submission_id, contest_id, problem_id, user_id, submitted_time,
                             result, status, created_at, due_at)
                        VALUES (?, ?, ?, ?, ?, 'ACCEPTED', ?, CURRENT_TIMESTAMP(6),
                                CASE WHEN ? IS NULL THEN NULL
                                     ELSE TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)) END)
                        """,
                persistSubmission(), contest.getId(), problem.getId(), user.getId(), SUBMITTED_AT,
                status, dueSecondsFromNow, dueSecondsFromNow);
    }

    private long persistSubmission() {
        String suffix = UUID.randomUUID().toString();
        ContestSubmission submission = ContestSubmission.create(
                contest, user, problem, "return 0;", suffix.replace("-", ""), SUBMITTED_AT);
        submission.assignId(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        entityManager.persist(submission);
        entityManager.flush();
        return submission.getId();
    }
}
