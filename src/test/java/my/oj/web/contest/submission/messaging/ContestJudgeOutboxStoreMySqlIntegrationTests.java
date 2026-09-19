package my.oj.web.contest.submission.messaging;

import jakarta.persistence.EntityManager;
import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultCommand;
import my.oj.web.contest.submission.judge.JdbcContestSubmissionJudgeResultBatchPersistence;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the claim SQL and fencing against the MySQL 8 test database, not H2. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestQuerydslConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContestJudgeOutboxStoreMySqlIntegrationTests {

    private static final String PREFIX = "mysql-judge-claim-it-";

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ContestJudgeOutboxStore store;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        store = new ContestJudgeOutboxStore(jdbcTemplate, transactionManager);
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        jdbcTemplate.update("DELETE FROM contest_submission WHERE code_hash LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM problem WHERE name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM contest WHERE name LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM user WHERE name LIKE ?", PREFIX + "%");
    }

    @Test
    void twoWorkersNeverClaimTheSameRows() throws Exception {
        List<TestSubmission> submissions = List.of(
                persistSubmission(), persistSubmission(), persistSubmission(), persistSubmission());
        submissions.forEach(submission -> insertOutbox(submission.submissionId(), "PENDING", null, 0));
        CyclicBarrier start = new CyclicBarrier(3);

        CompletableFuture<List<ContestJudgeOutboxStore.ClaimedEvent>> first = CompletableFuture.supplyAsync(
                () -> claimAfterBarrier(start, 2));
        CompletableFuture<List<ContestJudgeOutboxStore.ClaimedEvent>> second = CompletableFuture.supplyAsync(
                () -> claimAfterBarrier(start, 2));
        start.await(5, TimeUnit.SECONDS);

        List<ContestJudgeOutboxStore.ClaimedEvent> firstClaims = first.get(5, TimeUnit.SECONDS);
        List<ContestJudgeOutboxStore.ClaimedEvent> secondClaims = second.get(5, TimeUnit.SECONDS);
        Set<Long> firstIds = submissionIds(firstClaims);
        Set<Long> secondIds = submissionIds(secondClaims);

        assertThat(firstClaims).hasSize(2);
        assertThat(secondClaims).hasSize(2);
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        Set<Long> all = new HashSet<>(firstIds);
        all.addAll(secondIds);
        assertThat(all).containsExactlyInAnyOrderElementsOf(
                submissions.stream().map(TestSubmission::submissionId).toList());
    }

    @Test
    void expiredLeaseIsReclaimedAndOldTokenCannotCompleteIt() {
        TestSubmission submission = persistSubmission();
        long eventId = insertOutbox(submission.submissionId(), "PUBLISHING", "expired-owner", 1);

        ContestJudgeOutboxStore.ClaimedEvent reclaimed = store.claim(1, Duration.ofSeconds(1)).get(0);
        ContestJudgeOutboxStore.BatchCompletionResult stale = store.completeAll(
                List.of(new ContestJudgeOutboxStore.ClaimedEvent(
                        eventId, submission.submissionId(), "expired-owner")),
                List.of());
        ContestJudgeOutboxStore.BatchCompletionResult current =
                store.completeAll(List.of(reclaimed), List.of());

        assertThat(reclaimed.staleReclaim()).isTrue();
        assertThat(reclaimed.claimToken()).isNotEqualTo("expired-owner");
        assertThat(stale.staleCount()).isEqualTo(1);
        assertThat(current.publishedApplied()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM contest_judge_outbox WHERE id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void duplicateResultPersistenceKeepsOneStoredResult() {
        TestSubmission submission = persistSubmission();
        ContestSubmissionJudgeResultCommand command = new ContestSubmissionJudgeResultCommand(
                submission.submissionId(), submission.contestId(), null, null, null,
                LocalDateTime.now(), SubmissionResult.PARTIAL_ACCEPTED, LocalDateTime.now());
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                new JdbcContestSubmissionJudgeResultBatchPersistence(jdbcTemplate);

        persistence.persistAll(List.of(command, command));
        persistence.persistAll(List.of(command));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_result WHERE submission_id = ?",
                Long.class, submission.submissionId())).isEqualTo(1L);
    }

    private List<ContestJudgeOutboxStore.ClaimedEvent> claimAfterBarrier(CyclicBarrier barrier, int size) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return store.claim(size, Duration.ofSeconds(30));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Set<Long> submissionIds(List<ContestJudgeOutboxStore.ClaimedEvent> events) {
        Set<Long> ids = new HashSet<>();
        events.forEach(event -> ids.add(event.submissionId()));
        return ids;
    }

    private long insertOutbox(long submissionId, String status, String token, int attempts) {
        jdbcTemplate.update("""
                        INSERT INTO contest_judge_outbox
                            (submission_id, status, claim_token, claimed_at, attempts, created_at, updated_at)
                        VALUES (?, ?, ?,
                            CASE WHEN ? IS NULL THEN NULL ELSE TIMESTAMPADD(SECOND, -10, CURRENT_TIMESTAMP(6)) END,
                            ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                        """, submissionId, status, token, token, attempts);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM contest_judge_outbox WHERE submission_id = ?", Long.class, submissionId);
    }

    private TestSubmission persistSubmission() {
        return transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            User user = User.create(PREFIX + suffix, "pass");
            Contest contest = new Contest(PREFIX + suffix);
            entityManager.persist(user);
            entityManager.persist(contest);
            Problem problem = Problem.create(PREFIX + suffix, contest, 1L);
            entityManager.persist(problem);
            ContestSubmission submission = ContestSubmission.create(
                    contest, user, problem, "return 0;", PREFIX + suffix, LocalDateTime.now());
            submission.assignId(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
            entityManager.persist(submission);
            entityManager.flush();
            return new TestSubmission(submission.getId(), contest.getId());
        });
    }

    private record TestSubmission(long submissionId, long contestId) {
    }
}
