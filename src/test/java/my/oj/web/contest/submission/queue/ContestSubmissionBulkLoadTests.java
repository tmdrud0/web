package my.oj.web.contest.submission.queue;

import jakarta.persistence.EntityManagerFactory;
import my.oj.web.contest.Contest;
import my.oj.web.contest.ContestRepository;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import my.oj.web.contest.submission.core.ContestSubmissionRepository;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.SubmissionService;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.submission.bulk.batch-size=100",
        "contest.submission.bulk.worker-count=4",
        "contest.submission.bulk.flush-interval-millis=200",
        "contest.outbox.scheduler.enabled=false",
        "contest.submission.rate-limit.store=none",
        "contest.submission.dedup.store=memory",
        "spring.jpa.properties.hibernate.jdbc.batch_size=100",
        "spring.jpa.properties.hibernate.order_inserts=true"
})
@Tag("load-test")
@EnabledIfEnvironmentVariable(named = "INCLUDE_MYSQL_LOAD_TEST", matches = "true")
class ContestSubmissionBulkLoadTests {

    @DynamicPropertySource
    static void loadTestProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault(
                "MYSQL_LOAD_TEST_URL",
                "jdbc:mysql://localhost:3306/oj_codex_bulk_load_20260326"
                        + "?createDatabaseIfNotExist=true&rewriteBatchedStatements=true&cachePrepStmts=true"
        ));
    }

    private static final int USER_COUNT = 1000;
    private static final int PROBLEM_COUNT = 5;
    private static final int TOTAL_SUBMISSIONS = 5000;
    private static final int CONCURRENCY = 50;
    private static final int DIRECT_TOTAL_SUBMISSIONS = 12_800;
    private static final int DIRECT_BATCH_SIZE = 100;
    private static final int DIRECT_WORKERS = 8;

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContestSubmissionRepository contestSubmissionRepository;

    @Autowired
    private ContestSubmissionBulkMetrics bulkMetrics;

    @Autowired
    private ContestSubmissionBulkProcessor bulkProcessor;

    @Autowired
    private ContestSubmissionBatchPersistence batchPersistence;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<Long> contestIds = new ArrayList<>();
    private final List<Long> problemIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (!contestIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM contest_submission_result WHERE contest_id IN (" + placeholders(contestIds.size()) + ")",
                    contestIds.toArray());
            jdbcTemplate.update("DELETE FROM contest_submission_outbox WHERE contest_id IN (" + placeholders(contestIds.size()) + ")",
                    contestIds.toArray());
            jdbcTemplate.update("DELETE FROM contest_submission WHERE contest_id IN (" + placeholders(contestIds.size()) + ")",
                    contestIds.toArray());
        }
        if (!problemIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM problem WHERE id IN (" + placeholders(problemIds.size()) + ")",
                    problemIds.toArray());
        }
        if (!contestIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM contest WHERE id IN (" + placeholders(contestIds.size()) + ")",
                    contestIds.toArray());
        }
        if (!userIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM `user` WHERE id IN (" + placeholders(userIds.size()) + ")",
                    userIds.toArray());
        }
        bulkMetrics.reset();
    }

    @Test
    void contestSubmissionBulkWriterMaintainsBatchingUnderLoad() throws Exception {
        bulkMetrics.reset();

        List<Problem> problems = createContestProblems(PROBLEM_COUNT);
        List<User> users = createUsers(USER_COUNT);

        long startedAt = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<?>> futures = new ArrayList<>(TOTAL_SUBMISSIONS);
            for (int i = 0; i < TOTAL_SUBMISSIONS; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    User user = users.get(index % users.size());
                    Problem problem = problems.get(index % problems.size());
                    submissionService.submit(new SubmitSubmissionCommand(
                            user.getId(),
                            problem.getId(),
                            "load-code-" + index + "-" + UUID.randomUUID()
                    ));
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;

        var snapshot = bulkMetrics.snapshot();
        long inserted = contestSubmissionRepository.count();
        double throughput = TOTAL_SUBMISSIONS / elapsedSeconds;
        double averageChunkSize = snapshot.chunkCount() == 0
                ? 0.0
                : (double) snapshot.totalSubmissionCount() / snapshot.chunkCount();

        System.out.println("contest-submission-load-summary "
                + "totalSubmissions=" + TOTAL_SUBMISSIONS
                + " inserted=" + inserted
                + " elapsedSeconds=" + String.format(java.util.Locale.ROOT, "%.3f", elapsedSeconds)
                + " throughput=" + String.format(java.util.Locale.ROOT, "%.1f", throughput)
                + " chunkCount=" + snapshot.chunkCount()
                + " failedChunkCount=" + snapshot.failedChunkCount()
                + " averageChunkSize=" + String.format(java.util.Locale.ROOT, "%.2f", averageChunkSize)
                + " maxChunkSize=" + snapshot.maxChunkSize()
                + " maxActiveWorkers=" + snapshot.maxActiveWorkers());

        assertThat(snapshot.failedChunkCount()).isZero();
        assertThat(inserted).isEqualTo(TOTAL_SUBMISSIONS);
        assertThat(snapshot.chunkCount()).isGreaterThan(0);
        assertThat(snapshot.maxChunkSize()).isGreaterThan(1);
        assertThat(averageChunkSize).isGreaterThan(10.0);
    }

    @Test
    void contestSubmissionAndOutboxPersistenceThroughput() throws Exception {
        List<Problem> problems = createContestProblems(PROBLEM_COUNT);
        List<User> users = createUsers(USER_COUNT);
        AtomicInteger nextIndex = new AtomicInteger();
        LocalDateTime submittedTime = LocalDateTime.now();

        long startedAt = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(DIRECT_WORKERS);
        try {
            List<Future<?>> futures = new ArrayList<>(DIRECT_WORKERS);
            for (int worker = 0; worker < DIRECT_WORKERS; worker++) {
                futures.add(executor.submit(() -> {
                    while (true) {
                        int start = nextIndex.getAndAdd(DIRECT_BATCH_SIZE);
                        if (start >= DIRECT_TOTAL_SUBMISSIONS) {
                            return;
                        }
                        int end = Math.min(start + DIRECT_BATCH_SIZE, DIRECT_TOTAL_SUBMISSIONS);
                        List<ContestSubmissionWriteRequest> requests = new ArrayList<>(end - start);
                        for (int index = start; index < end; index++) {
                            User user = users.get(index % users.size());
                            Problem problem = problems.get(index % problems.size());
                            String code = "direct-code-" + index;
                            requests.add(new ContestSubmissionWriteRequest(
                                    problem.getContest().getId(),
                                    problem.getId(),
                                    user.getId(),
                                    code,
                                    String.format(java.util.Locale.ROOT, "%064x", index),
                                    submittedTime,
                                    900_000_000_000_000_000L + index
                            ));
                        }
                        bulkProcessor.process(requests);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        long insertedSubmissions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission WHERE contest_id = ?",
                Long.class,
                contestIds.get(0)
        );
        long insertedOutbox = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM contest_judge_outbox o
                JOIN contest_submission s ON s.id = o.submission_id
                WHERE s.contest_id = ?
                """,
                Long.class,
                contestIds.get(0)
        );
        double throughput = DIRECT_TOTAL_SUBMISSIONS / elapsedSeconds;

        System.out.println("contest-submission-direct-persistence-summary "
                + "mode=" + batchPersistence.getClass().getSimpleName()
                + " totalSubmissions=" + DIRECT_TOTAL_SUBMISSIONS
                + " insertedSubmissions=" + insertedSubmissions
                + " insertedOutbox=" + insertedOutbox
                + " elapsedSeconds=" + String.format(java.util.Locale.ROOT, "%.3f", elapsedSeconds)
                + " throughput=" + String.format(java.util.Locale.ROOT, "%.1f", throughput)
                + " workers=" + DIRECT_WORKERS
                + " batchSize=" + DIRECT_BATCH_SIZE);

        assertThat(insertedSubmissions).isEqualTo(DIRECT_TOTAL_SUBMISSIONS);
        assertThat(insertedOutbox).isEqualTo(DIRECT_TOTAL_SUBMISSIONS);
    }

    private List<Problem> createContestProblems(int count) {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = new Contest("load-bulk-" + UUID.randomUUID());
        ReflectionTestUtils.setField(contest, "startTime", now.minusMinutes(5));
        ReflectionTestUtils.setField(contest, "endTime", now.plusMinutes(30));
        Contest savedContest = contestRepository.save(contest);
        contestIds.add(savedContest.getId());

        List<Problem> problems = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Problem savedProblem = problemRepository.save(Problem.create("P" + (i + 1), savedContest, (long) (i + 1)));
            problemIds.add(savedProblem.getId());
            problems.add(savedProblem);
        }
        return problems;
    }

    private List<User> createUsers(int count) {
        List<User> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            User saved = userRepository.save(User.create("load_user_" + UUID.randomUUID(), "pw"));
            userIds.add(saved.getId());
            users.add(saved);
        }
        return users;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
