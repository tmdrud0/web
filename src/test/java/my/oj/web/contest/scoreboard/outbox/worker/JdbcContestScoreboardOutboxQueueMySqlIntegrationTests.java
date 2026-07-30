package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutbox;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import jakarta.persistence.EntityManager;
import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.testsupport.ContestScoreboardTestData;
import my.oj.web.testsupport.ContestScoreboardTestData.SeededContest;
import my.oj.web.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        TestQuerydslConfig.class
})
class JdbcContestScoreboardOutboxQueueMySqlIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 3, 10, 12, 0);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcContestScoreboardOutboxQueue outboxQueue;

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

    /**
     * There is no worker-level lock any more, so several workers claim from the same queue at
     * once. {@code FOR UPDATE SKIP LOCKED} has to hand every row to exactly one of them.
     *
     * <p>Runs outside the test transaction: the seeded rows have to be committed before other
     * connections can claim them.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentWorkersNeverClaimTheSameRowTwice() throws Exception {
        int rowCount = 24;
        int workerCount = 4;
        Duration lease = Duration.ofSeconds(30);
        SeededContest seeded = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "scoreboard-concurrent-claim", CONTEST_START, 1, 1);
        try {
            List<Long> outboxIds = commitPendingOutboxRows(seeded, rowCount);

            CountDownLatch startTogether = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            List<Long> claimedIds = new ArrayList<>();
            Set<String> claimTokens = new HashSet<>();
            try {
                List<Future<List<JdbcContestScoreboardOutboxQueue.ClaimedEvent>>> claims = new ArrayList<>();
                for (int worker = 0; worker < workerCount; worker++) {
                    claims.add(executor.submit(() -> {
                        startTogether.await(5, TimeUnit.SECONDS);
                        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> claimed = new ArrayList<>();
                        // Each worker keeps claiming until the queue hands it nothing.
                        while (true) {
                            List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> batch = outboxQueue.claim(5, lease);
                            if (batch.isEmpty()) {
                                return claimed;
                            }
                            claimed.addAll(batch);
                        }
                    }));
                }
                startTogether.countDown();

                for (Future<List<JdbcContestScoreboardOutboxQueue.ClaimedEvent>> claim : claims) {
                    for (JdbcContestScoreboardOutboxQueue.ClaimedEvent event : claim.get(30, TimeUnit.SECONDS)) {
                        claimedIds.add(event.eventId());
                        claimTokens.add(event.claimToken());
                    }
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(claimedIds).doesNotHaveDuplicates();
            assertThat(claimedIds).containsExactlyInAnyOrderElementsOf(outboxIds);
            assertThat(claimTokens).as("each claim batch carries its own lease token").hasSizeGreaterThan(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM contest_submission_outbox
                    WHERE contest_id = ? AND status = 'PROCESSING'
                    """, Long.class, seeded.contestId()))
                    .isEqualTo(rowCount);
        } finally {
            ContestScoreboardTestData.deleteContest(jdbcTemplate, seeded.contestId());
        }
    }

    private List<Long> commitPendingOutboxRows(SeededContest seeded, int rowCount) {
        List<ContestScoreboardTestData.Attempt> attempts = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            attempts.add(new ContestScoreboardTestData.Attempt(
                    930_000_000_000_000_000L + index,
                    seeded.problemIds().get(0),
                    seeded.userIds().get(0),
                    index,
                    index + 1,
                    SubmissionResult.ACCEPTED
            ));
        }
        ContestScoreboardTestData.insertAttempts(jdbcTemplate, seeded.contestId(), CONTEST_START, attempts, false);
        return jdbcTemplate.queryForList(
                "SELECT id FROM contest_submission_outbox WHERE contest_id = ? ORDER BY id",
                Long.class,
                seeded.contestId()
        );
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
