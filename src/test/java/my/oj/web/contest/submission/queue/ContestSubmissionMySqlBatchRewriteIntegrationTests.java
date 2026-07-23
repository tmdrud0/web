package my.oj.web.contest.submission.queue;

import my.oj.web.contest.Contest;
import my.oj.web.contest.ContestRepository;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.SubmissionService;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.jdbc.mutation.internal.StandardMutationExecutorService;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.mutation.InsertCoordinatorStandard;
import org.hibernate.sql.model.MutationOperationGroup;
import org.hibernate.sql.model.PreparableMutationOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/oj_codex_batch_verify_20260326?createDatabaseIfNotExist=true&rewriteBatchedStatements=true&cachePrepStmts=true",
        "contest.submission.writer.mode=bulk",
        "contest.submission.bulk.batch-size=3",
        "contest.submission.bulk.worker-count=1",
        "contest.submission.bulk.flush-interval-millis=5000",
        "contest.outbox.immediate.enabled=false",
        "contest.outbox.scheduler.enabled=false",
        "contest.submission.rate-limit.store=none",
        "contest.submission.dedup.store=memory",
        "spring.jpa.properties.hibernate.jdbc.batch_size=100",
        "spring.jpa.properties.hibernate.order_inserts=true"
})
@EnabledIfEnvironmentVariable(named = "INCLUDE_MYSQL_BATCH_VERIFICATION", matches = "true")
@Import(JdbcBatchProbeTestConfiguration.class)
class ContestSubmissionMySqlBatchRewriteIntegrationTests {

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContestSubmissionBulkMetrics bulkMetrics;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcBatchProbe jdbcBatchProbe;

    private final List<Long> contestIds = new ArrayList<>();
    private final List<Long> problemIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        disableGeneralLog();
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
        jdbcBatchProbe.reset();
    }

    @Test
    void contestSubmissionInsertIsChunkedAndRewrittenOnMysql() throws Exception {
        bulkMetrics.reset();
        enableGeneralLog();

        Problem problem = createContestProblem();
        List<User> users = createUsers(3);

        ExecutorService executor = Executors.newFixedThreadPool(users.size());
        try {
            List<CompletableFuture<Void>> futures = users.stream()
                    .map(user -> CompletableFuture.runAsync(
                            () -> submissionService.submit(new SubmitSubmissionCommand(
                                    user.getId(),
                                    problem.getId(),
                                    "code-" + user.getId() + "-" + UUID.randomUUID()
                            )),
                            executor
                    ))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        disableGeneralLog();

        ContestSubmissionBulkMetrics.Snapshot snapshot = bulkMetrics.snapshot();
        assertThat(snapshot.failedChunkCount()).isZero();
        assertThat(snapshot.chunkCount()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.maxChunkSize()).isGreaterThanOrEqualTo(3);

        List<String> statements = jdbcTemplate.query(
                """
                SELECT argument
                FROM mysql.general_log
                WHERE argument IS NOT NULL
                ORDER BY event_time
                """,
                (rs, rowNum) -> rs.getString("argument")
        ).stream()
                .map(JdbcBatchProbeTestConfiguration::normalizeSql)
                .filter(sql -> sql.contains("insert into contest_submission"))
                .toList();

        assertThat(statements)
                .withFailMessage("No contest_submission insert was captured in mysql.general_log")
                .isNotEmpty();

        assertThat(statements)
                .withFailMessage("Expected a rewritten multi-value insert, but captured statements were: %s", statements)
                .anyMatch(ContestSubmissionMySqlBatchRewriteIntegrationTests::isMultiValueInsert);
    }

    @Test
    void contestSubmissionInsertUsesJdbcBatchAtDriverLevel() throws Exception {
        bulkMetrics.reset();
        jdbcBatchProbe.reset();

        Problem problem = createContestProblem();
        List<User> users = createUsers(3);

        ExecutorService executor = Executors.newFixedThreadPool(users.size());
        try {
            List<CompletableFuture<Void>> futures = users.stream()
                    .map(user -> CompletableFuture.runAsync(
                            () -> submissionService.submit(new SubmitSubmissionCommand(
                                    user.getId(),
                                    problem.getId(),
                                    "code-" + user.getId() + "-" + UUID.randomUUID()
                            )),
                            executor
                    ))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        ContestSubmissionBulkMetrics.Snapshot snapshot = bulkMetrics.snapshot();
        assertThat(snapshot.failedChunkCount()).isZero();
        assertThat(snapshot.maxChunkSize()).isGreaterThanOrEqualTo(3);

        assertThat(jdbcBatchProbe.targetSqls())
                .withFailMessage("No contest_submission prepared statement was observed")
                .isNotEmpty();
        assertThat(jdbcBatchProbe.addBatchCount())
                .withFailMessage("Expected Hibernate to call addBatch, but probe captured %s", jdbcBatchProbe.events())
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbcBatchProbe.executeBatchCount())
                .withFailMessage("Expected Hibernate to call executeBatch, but probe captured %s", jdbcBatchProbe.events())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void contestSubmissionMetadataShowsInsertIsBatchable() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        AbstractEntityPersister persister = (AbstractEntityPersister) sessionFactory
                .getRuntimeMetamodels()
                .getEntityMappingType(ContestSubmission.class);

        InsertCoordinatorStandard insertCoordinator = (InsertCoordinatorStandard) persister.getInsertCoordinator();
        MutationOperationGroup operationGroup = insertCoordinator.getStaticMutationOperationGroup();
        PreparableMutationOperation operation = (PreparableMutationOperation) operationGroup.getSingleOperation();

        assertThat(persister.isIdentifierAssignedByInsert()).isFalse();
        assertThat(persister.hasInsertGeneratedProperties()).isFalse();
        assertThat(persister.getEntityMetamodel().isDynamicInsert()).isFalse();
        assertThat(insertCoordinator.getInsertBatchKey()).isNotNull();
        assertThat(operation.canBeBatched(insertCoordinator.getInsertBatchKey(), 100)).isTrue();
        assertThat(operation.getExpectation().canBeBatched()).isTrue();
    }

    @Test
    void hibernateEffectiveBatchSizeIsConfiguredAboveOne() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        StandardMutationExecutorService mutationExecutorService =
                (StandardMutationExecutorService) sessionFactory.getServiceRegistry()
                        .getService(org.hibernate.engine.jdbc.mutation.spi.MutationExecutorService.class);

        Object globalBatchSize = ReflectionTestUtils.getField(mutationExecutorService, "globalBatchSize");

        assertThat(globalBatchSize).isEqualTo(100);
    }

    @Test
    void hibernateCreatesBatchedMutationExecutorForContestSubmissionInsert() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        AbstractEntityPersister persister = (AbstractEntityPersister) sessionFactory
                .getRuntimeMetamodels()
                .getEntityMappingType(ContestSubmission.class);
        InsertCoordinatorStandard insertCoordinator = (InsertCoordinatorStandard) persister.getInsertCoordinator();
        MutationOperationGroup operationGroup = insertCoordinator.getStaticMutationOperationGroup();
        StandardMutationExecutorService mutationExecutorService =
                (StandardMutationExecutorService) sessionFactory.getServiceRegistry()
                        .getService(org.hibernate.engine.jdbc.mutation.spi.MutationExecutorService.class);

        try (var entityManager = entityManagerFactory.createEntityManager()) {
            Object session = entityManager.unwrap(org.hibernate.engine.spi.SharedSessionContractImplementor.class);
            Object executor = mutationExecutorService.createExecutor(
                    insertCoordinator::getInsertBatchKey,
                    operationGroup,
                    (org.hibernate.engine.spi.SharedSessionContractImplementor) session
            );

            Integer sessionBatchSize = ((org.hibernate.engine.spi.SharedSessionContractImplementor) session)
                    .getJdbcCoordinator()
                    .getJdbcSessionOwner()
                    .getJdbcBatchSize();

            assertThat(sessionBatchSize).isNull();
            assertThat(executor.getClass().getName())
                    .isEqualTo("org.hibernate.engine.jdbc.mutation.internal.MutationExecutorSingleBatched");
        }
    }

    @Test
    void plainJdbcBatchRewritesEquivalentContestSubmissionInsert() throws Exception {
        enableGeneralLog();

        Problem problem = createContestProblem();
        List<User> users = createUsers(3);
        long baseId = 9_000_000_000L;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("""
                     INSERT INTO contest_submission
                     (code, code_hash, contest_id, problem_id, submitted_time, user_id, id)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            for (int i = 0; i < users.size(); i++) {
                User user = users.get(i);
                ps.setString(1, "jdbc-code-" + user.getId());
                ps.setString(2, UUID.randomUUID().toString().replace("-", ""));
                ps.setLong(3, problem.getContest().getId());
                ps.setLong(4, problem.getId());
                ps.setObject(5, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                ps.setLong(6, user.getId());
                ps.setLong(7, baseId + i);
                ps.addBatch();
            }
            ps.executeBatch();
        } finally {
            disableGeneralLog();
        }

        List<String> statements = jdbcTemplate.query(
                """
                SELECT argument
                FROM mysql.general_log
                WHERE argument IS NOT NULL
                ORDER BY event_time
                """,
                (rs, rowNum) -> rs.getString("argument")
        ).stream()
                .map(JdbcBatchProbeTestConfiguration::normalizeSql)
                .filter(sql -> sql.contains("insert into contest_submission"))
                .toList();

        assertThat(statements)
                .withFailMessage("No contest_submission insert was captured for plain JDBC batch")
                .isNotEmpty();
        assertThat(statements)
                .withFailMessage("Expected plain JDBC batch to rewrite, but captured statements were: %s", statements)
                .anyMatch(ContestSubmissionMySqlBatchRewriteIntegrationTests::isMultiValueInsert);
    }

    private Problem createContestProblem() {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = new Contest("batch-verify-" + UUID.randomUUID());
        ReflectionTestUtils.setField(contest, "startTime", now.minusMinutes(5));
        ReflectionTestUtils.setField(contest, "endTime", now.plusMinutes(5));
        Contest savedContest = contestRepository.save(contest);
        contestIds.add(savedContest.getId());

        Problem savedProblem = problemRepository.save(Problem.create("A", savedContest, 1L));
        problemIds.add(savedProblem.getId());
        return savedProblem;
    }

    private List<User> createUsers(int count) {
        List<User> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            User saved = userRepository.save(User.create("batch_user_" + UUID.randomUUID(), "pw"));
            userIds.add(saved.getId());
            users.add(saved);
        }
        return users;
    }

    private void enableGeneralLog() {
        jdbcTemplate.execute("SET GLOBAL log_output = 'TABLE'");
        jdbcTemplate.execute("SET GLOBAL general_log = 'OFF'");
        jdbcTemplate.execute("TRUNCATE TABLE mysql.general_log");
        jdbcTemplate.execute("SET GLOBAL general_log = 'ON'");
    }

    private void disableGeneralLog() {
        try {
            jdbcTemplate.execute("SET GLOBAL general_log = 'OFF'");
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean isMultiValueInsert(String sql) {
        int valuesIndex = sql.indexOf(" values ");
        if (valuesIndex < 0) {
            return false;
        }
        String valuesClause = sql.substring(valuesIndex);
        return valuesClause.contains("),(") || valuesClause.contains("), (");
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

}
