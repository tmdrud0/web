package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutbox;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import jakarta.persistence.EntityManager;
import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JdbcContestScoreboardOutboxQueue.class,
        ContestScoreboardOutboxProcessLock.class,
        TestQuerydslConfig.class
})
class JdbcContestScoreboardOutboxQueueMySqlIntegrationTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcContestScoreboardOutboxQueue outboxQueue;

    @Autowired
    private ContestScoreboardOutboxProcessLock processLock;

    @Test
    void expiredLeaseCanBeReclaimedAndRejectsThePreviousWorkerCompletion() {
        ContestScoreboardOutbox outbox = persistPendingOutbox();
        Duration lease = Duration.ofSeconds(30);

        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> firstClaim = outboxQueue.claim(1, lease);
        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> whileLeaseIsActive = outboxQueue.claim(1, lease);

        assertThat(firstClaim).hasSize(1);
        assertThat(firstClaim.get(0).eventId()).isEqualTo(outbox.getId());
        assertThat(whileLeaseIsActive).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contest_submission_outbox WHERE id = ?",
                String.class,
                outbox.getId()
        )).isEqualTo("PROCESSING");

        jdbcTemplate.update("""
                UPDATE contest_submission_outbox
                SET claimed_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
                WHERE id = ?
                """, outbox.getId());

        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> reclaimed = outboxQueue.claim(1, lease);

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).eventId()).isEqualTo(outbox.getId());
        assertThat(reclaimed.get(0).claimToken()).isNotEqualTo(firstClaim.get(0).claimToken());

        JdbcContestScoreboardOutboxQueue.BatchCompletionResult staleCompletion = outboxQueue.completeAll(
                List.of(new JdbcContestScoreboardOutboxQueue.CompletedEvent(firstClaim.get(0), 70L)),
                List.of()
        );
        JdbcContestScoreboardOutboxQueue.BatchCompletionResult currentCompletion = outboxQueue.completeAll(
                List.of(new JdbcContestScoreboardOutboxQueue.CompletedEvent(reclaimed.get(0), 71L)),
                List.of()
        );

        assertThat(staleCompletion.staleCount()).isOne();
        assertThat(currentCompletion.staleCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT redis_seq FROM contest_submission_outbox WHERE id = ?",
                Long.class,
                outbox.getId()
        )).isEqualTo(71L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contest_submission_outbox WHERE id = ?",
                String.class,
                outbox.getId()
        )).isEqualTo("COMPLETED");
    }

    @Test
    void failedEventIsNotImmediatelyReclaimedButBecomesEligibleAfterBackoff() {
        ContestScoreboardOutbox outbox = persistPendingOutbox();
        Duration lease = Duration.ofSeconds(30);
        JdbcContestScoreboardOutboxQueue.ClaimedEvent claimed = outboxQueue.claim(1, lease).get(0);

        JdbcContestScoreboardOutboxQueue.BatchCompletionResult failure = outboxQueue.completeAll(
                List.of(),
                List.of(new JdbcContestScoreboardOutboxQueue.FailedEvent(claimed, "redis unavailable"))
        );

        assertThat(failure.failedApplied()).isOne();
        assertThat(outboxQueue.claim(1, lease)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_attempt_at IS NOT NULL FROM contest_submission_outbox WHERE id = ?",
                Boolean.class,
                outbox.getId()
        )).isTrue();

        jdbcTemplate.update("""
                UPDATE contest_submission_outbox
                SET next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)
                WHERE id = ?
                """, outbox.getId());

        assertThat(outboxQueue.claim(1, lease))
                .singleElement()
                .extracting(JdbcContestScoreboardOutboxQueue.ClaimedEvent::eventId)
                .isEqualTo(outbox.getId());
    }

    @Test
    void namedLockAllowsOnlyOneScoreboardBatchWorkerAcrossConnections() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> first = executor.submit(() -> processLock.executeIfAcquired(() -> {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the scoreboard process lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the scoreboard process lock", exception);
                }
                return "first";
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Optional<String> blocked = processLock.executeIfAcquired(() -> "second");
            assertThat(blocked).isEmpty();

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).contains("first");
            assertThat(processLock.executeIfAcquired(() -> "third")).contains("third");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private ContestScoreboardOutbox persistPendingOutbox() {
        String suffix = UUID.randomUUID().toString();
        User user = User.create("scoreboard-claim-" + suffix, "pass");
        Contest contest = new Contest("scoreboard-claim-" + suffix);
        entityManager.persist(user);
        entityManager.persist(contest);

        Problem problem = Problem.create("scoreboard-claim-" + suffix, contest, 1L);
        entityManager.persist(problem);
        entityManager.flush();

        LocalDateTime submittedAt = LocalDateTime.of(2026, 3, 10, 12, 1);
        ContestSubmission submission = ContestSubmission.create(
                contest,
                user,
                problem,
                "return 0;",
                suffix.replace("-", ""),
                submittedAt
        );
        submission.assignId(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        entityManager.persist(submission);

        ContestScoreboardOutbox outbox = ContestScoreboardOutbox.pending(
                submission.getId(),
                contest.getId(),
                problem.getId(),
                user.getId(),
                LocalDateTime.of(2026, 3, 10, 12, 0),
                submittedAt,
                SubmissionResult.ACCEPTED,
                LocalDateTime.of(2026, 3, 10, 12, 2),
                null
        );
        entityManager.persist(outbox);
        entityManager.flush();
        return outbox;
    }
}
