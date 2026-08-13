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

/** Executes the remaining judge-outbox diagnostics against their MySQL-specific SQL. */
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
        user = User.create("judge-outbox-backlog-" + suffix, "pass");
        contest = new Contest("judge-outbox-backlog-" + suffix);
        entityManager.persist(user);
        entityManager.persist(contest);
        problem = Problem.create("judge-outbox-backlog-" + suffix, contest, 1L);
        entityManager.persist(problem);
        entityManager.flush();
    }

    @Test
    void countsOnlyWaitingJudgeRows() {
        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();
        double pendingBefore = backlog("PENDING");
        double publishingBefore = backlog("PUBLISHING");

        insertJudgeOutbox("PENDING", 0);
        insertJudgeOutbox("PENDING", 0);
        insertJudgeOutbox("PUBLISHING", 0);
        insertJudgeOutbox("PUBLISHED", 0);
        metrics.poll();

        assertThat(backlog("PENDING") - pendingBefore).isEqualTo(2.0);
        assertThat(backlog("PUBLISHING") - publishingBefore).isEqualTo(1.0);
        assertThat(registry.find("contest.outbox.backlog").tag("outbox", "scoreboard").gauge()).isNull();
    }

    @Test
    void stopsCountingAtConfiguredCap() {
        for (int index = 0; index < 5; index++) {
            insertJudgeOutbox("PENDING", 0);
        }

        ContestOutboxBacklogMetrics metrics = metrics(2);
        metrics.poll();

        assertThat(backlog("PENDING") + backlog("PUBLISHING")).isEqualTo(2.0);
    }

    @Test
    void measuresPendingHeadAgeOnMysqlClock() {
        insertJudgeOutbox("PENDING", -90);

        ContestOutboxBacklogMetrics metrics = metrics(1_000);
        metrics.poll();

        assertThat(registry.get("contest.outbox.head.lag")
                .tag("outbox", "judge").gauge().value()).isGreaterThanOrEqualTo(90.0);
    }

    private ContestOutboxBacklogMetrics metrics(int cap) {
        ContestOutboxBacklogMetrics metrics = new ContestOutboxBacklogMetrics(
                jdbcTemplate, new ContestOutboxMetricsProperties(cap));
        metrics.bindTo(registry);
        return metrics;
    }

    private double backlog(String status) {
        return registry.get("contest.outbox.backlog")
                .tags("outbox", "judge", "status", status).gauge().value();
    }

    private void insertJudgeOutbox(String status, int createdSecondsFromNow) {
        jdbcTemplate.update("""
                        INSERT INTO contest_judge_outbox (submission_id, status, created_at)
                        VALUES (?, ?, TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)))
                        """,
                persistSubmission(), status, createdSecondsFromNow);
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
