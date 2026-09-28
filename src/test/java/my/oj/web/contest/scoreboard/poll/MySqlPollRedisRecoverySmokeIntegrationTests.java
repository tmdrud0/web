package my.oj.web.contest.scoreboard.poll;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardSequencedApplier;
import my.oj.web.contest.scoreboard.redis.RedisScoreboardSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole {@code mysql-poll} delivery against a real MySQL and a real Redis, with nothing from RabbitMQ
 * on the classpath of the path under test: results are written to MySQL as the judge writes them, the
 * poller runs on its own threads, Redis is restored to an older snapshot while results keep arriving,
 * and at the end the scoreboard is compared with one rebuilt from MySQL alone.
 *
 * <ol>
 *   <li>judged results accumulate in MySQL; the poller applies them</li>
 *   <li>a Redis snapshot is taken; more results are applied</li>
 *   <li>the snapshot is restored while a writer keeps judging new results</li>
 *   <li>the periodic check sees {@code R < H}, persists {@code (R, H]} and fences the allocator</li>
 *   <li>new results keep flowing above H while the range is recovered chunk by chunk</li>
 *   <li>the DB-to-Redis digest matches, no sequence is held twice, nothing judged is left unapplied</li>
 * </ol>
 *
 * <p>The figures it measures - resume latency and tail recovery time - are printed and written to
 * {@code build/reports/mysql-poll-smoke.txt}. They are a smoke reading on a container, not a benchmark.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlPollRedisRecoverySmokeIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = MySqlPollTestDatabase.container();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final long CONTEST = 1L;
    private static final int USERS = 25;
    private static final int PROBLEMS = 5;
    private static final String[] VERDICTS = {"WRONG_ANSWER", "ACCEPTED", "TIME_LIMIT", "ACCEPTED", "RUNTIME_ERROR"};

    private MySqlPollTestDatabase database;
    private LettuceConnectionFactory liveConnection;
    private StringRedisTemplate live;
    private LettuceConnectionFactory digestConnection;
    private StringRedisTemplate digest;
    private final AtomicLong nextSubmission = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        database = new MySqlPollTestDatabase(MYSQL);
        database.migrateFromScratch();
        database.contest(CONTEST, USERS, PROBLEMS);
        liveConnection = connection(0);
        live = template(liveConnection);
        digestConnection = connection(1);
        digest = template(digestConnection);
        for (LettuceConnectionFactory factory : List.of(liveConnection, digestConnection)) {
            try (RedisConnection connection = factory.getConnection()) {
                connection.serverCommands().flushDb();
            }
        }
    }

    @AfterEach
    void tearDown() {
        liveConnection.destroy();
        digestConnection.destroy();
    }

    @Test
    void aRedisRestoreIsDetectedFencedAndRecoveredWhileNewResultsKeepFlowing() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingLedger ledger = new RecordingLedger(
                new JdbcContestScoreboardSequenceLedger(database.jdbc, database.transactionManager));
        ContestScoreboardSequencedApplier applier = new RedisContestScoreboardSequencedApplier(live);
        ContestScoreboardApplyLock lock = new ContestScoreboardApplyLock();
        ContestScoreboardMySqlPollMetrics metrics = new ContestScoreboardMySqlPollMetrics(registry);
        ContestScoreboardSequencedApplication application = new ContestScoreboardSequencedApplication(applier, ledger);
        ContestScoreboardRollbackDetector detector = new ContestScoreboardRollbackDetector(applier, ledger, lock, metrics);
        ContestScoreboardMySqlPollProperties properties = new ContestScoreboardMySqlPollProperties(
                100, Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofMillis(50), 50, 1000, "smoke-lock");
        ContestScoreboardMySqlPollLifecycle lifecycle = new ContestScoreboardMySqlPollLifecycle(
                new ContestScoreboardMySqlPoller(ledger, application, detector, lock, metrics, properties.batchSize()),
                detector,
                new ContestScoreboardRangeRecovery(ledger, application, detector, lock, metrics,
                        properties.recoveryChunkSize(), properties.recoveryMaxIterations()),
                new MySqlNamedLockPollOwnership(database.dataSource, properties.ownershipLockName()),
                metrics, properties);

        // 1-2. Judged results accumulate, a few stay PENDING; the poller applies the judged ones.
        judge(600, true);
        lifecycle.start();
        try {
            awaitUntil("the first 600 results are applied", () -> database.unsequencedJudged() == 0);

            // 3. Snapshot, then 400 more results reach MySQL and Redis.
            RedisScoreboardSnapshot snapshot = RedisScoreboardSnapshot.take(live);
            long allocatorAtSnapshot = applier.allocatorSequence();
            judge(400, false);
            awaitUntil("the next 400 results are applied", () -> database.unsequencedJudged() == 0);
            long watermarkBeforeRestore = database.watermark();
            assertThat(watermarkBeforeRestore).isGreaterThan(allocatorAtSnapshot);

            // 4-5. Restore the old snapshot while a writer keeps judging new results.
            AtomicBoolean writing = new AtomicBoolean(true);
            List<Long> judgedAfterRestore = new CopyOnWriteArrayList<>();
            Thread writer = new Thread(() -> {
                while (writing.get()) {
                    judgedAfterRestore.add(judgeOne(false));
                    sleep(5);
                }
            }, "smoke-judge");
            snapshot.restoreInto(live);
            long restoredAt = System.nanoTime();
            writer.start();

            // 6-8. The periodic check (no poll batch is needed to find it) persists (R, H] and fences.
            awaitUntil("the rollback is detected", () -> !ledger.pendingRanges().isEmpty() || rangesOpened() > 0);
            ContestScoreboardRecoveryRange range = firstRange();
            assertThat(range.fromExclusive()).isEqualTo(allocatorAtSnapshot);
            assertThat(range.throughInclusive()).isEqualTo(watermarkBeforeRestore);

            // 9. New results resume above H while the range is still being recovered.
            awaitUntil("a result judged after the restore is applied above H", () -> judgedAfterRestore.stream()
                    .map(database::sequenceOf)
                    .anyMatch(sequence -> sequence != null && sequence > watermarkBeforeRestore));
            long resumedAt = System.nanoTime();

            // 10. The bounded range is recovered in the background.
            awaitUntil("the range is recovered", () -> rangesPending() == 0);
            long recoveredAt = System.nanoTime();

            writing.set(false);
            writer.join();
            awaitUntil("every judged result is applied", () -> database.unsequencedJudged() == 0);
            lifecycle.stop();

            // 11. The scoreboard equals one rebuilt from MySQL alone, and nothing was double-issued.
            rebuildIntoDigest();
            assertThat(standings(live)).isEqualTo(standings(digest));
            assertThat(database.duplicateSequences()).isZero();
            assertThat(database.unsequencedJudged()).isZero();
            assertThat(ledger.rangeQueries)
                    .as("every recovery read is bounded by the persisted range - no full replay, no scan")
                    .allSatisfy(bounds -> assertThat(bounds).containsExactly(range.fromExclusive(), range.throughInclusive()));

            long total = database.jdbc.queryForObject("SELECT COUNT(*) FROM contest_submission_result", Long.class);
            String report = String.join(System.lineSeparator(),
                    "mysql-poll smoke (MySQL 8.0 + Redis 7 containers)",
                    "results in MySQL: " + total + " (PENDING kept out: " + pending() + ")",
                    "judged after the restore while recovering: " + judgedAfterRestore.size(),
                    "allocator at snapshot R=" + allocatorAtSnapshot + ", watermark before restore H=" + watermarkBeforeRestore,
                    "lost range (R,H] size: " + (watermarkBeforeRestore - allocatorAtSnapshot),
                    "range queries issued: " + ledger.rangeQueries.size() + " (all bounded by (R,H])",
                    "restore -> first new result applied above H: " + millis(resumedAt - restoredAt) + " ms",
                    "restore -> lost range fully recovered: " + millis(recoveredAt - restoredAt) + " ms",
                    "rollbacks detected: " + counter(registry, "contest.scoreboard.mysql.poll.rollbacks"),
                    "results recovered from the range: " + counter(registry, "contest.scoreboard.mysql.poll.recovery.applied"),
                    "duplicate sequences in MySQL: " + database.duplicateSequences(),
                    "judged results left unapplied: " + database.unsequencedJudged(),
                    "RabbitMQ Stream publishes/consumes/reconsumes: 0/0/0 (no Stream component in this path)",
                    "DB-to-Redis standings digest: equal (" + standings(live).size() + " keys)");
            System.out.println(report);
            Path out = Path.of("build", "reports", "mysql-poll-smoke.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report + System.lineSeparator());
        } finally {
            lifecycle.stop();
        }
    }

    /** Rebuilds the standings from MySQL alone into Redis database 1, through the same script. */
    private void rebuildIntoDigest() {
        RedisContestScoreboardSequencedApplier rebuild = new RedisContestScoreboardSequencedApplier(digest);
        JdbcContestScoreboardSequenceLedger ledger =
                new JdbcContestScoreboardSequenceLedger(database.jdbc, database.transactionManager);
        long through = database.jdbc.queryForObject("SELECT MAX(submission_id) FROM contest_submission_result", Long.class);
        long after = Long.MIN_VALUE;
        while (true) {
            List<ContestScoreboardSequencedResult> page = database.jdbc.query("""
                    SELECT csr.submission_id, csr.scoreboard_applied_seq, csr.contest_id, cs.problem_id, cs.user_id,
                           c.start_time, cs.submitted_time, COALESCE(csr.final_result, csr.provisional_result) AS result
                    FROM contest_submission_result csr
                    JOIN contest_submission cs ON cs.id = csr.submission_id
                    JOIN contest c ON c.id = csr.contest_id
                    WHERE COALESCE(csr.final_result, csr.provisional_result) <> 'PENDING'
                      AND csr.submission_id > ? AND csr.submission_id <= ?
                    ORDER BY csr.submission_id LIMIT 500
                    """, (rs, rowNum) -> new ContestScoreboardSequencedResult(
                    rs.getLong(1), null, rs.getLong(3), rs.getLong(4), rs.getLong(5),
                    rs.getTimestamp(6).toLocalDateTime(), rs.getTimestamp(7).toLocalDateTime(),
                    my.oj.web.submission.SubmissionResult.valueOf(rs.getString(8))), after, through);
            if (page.isEmpty()) {
                break;
            }
            for (ContestScoreboardSequencedResult row : page) {
                rebuild.apply(row.toUpdate(), 0, 0);
            }
            after = page.get(page.size() - 1).submissionId();
        }
        assertThat(ledger.highestDurableSequence()).isPositive();
    }

    /** Ranking, summaries and problem states - the standings, without the sequence bookkeeping. */
    private static Map<String, Object> standings(StringRedisTemplate redis) {
        Map<String, Object> state = new TreeMap<>();
        Set<String> keys = redis.keys("contest:scoreboard:" + CONTEST + ":*");
        for (String key : keys) {
            if (key.endsWith(":ranking")) {
                state.put(key, redis.opsForZSet().rangeWithScores(key, 0, -1).stream()
                        .map(tuple -> tuple.getValue() + "=" + tuple.getScore()).toList());
            } else if (key.endsWith(":processed")) {
                state.put(key, new TreeMap<>(Map.of("size", redis.opsForSet().size(key))));
            } else if (!key.endsWith(":lock")) {
                state.put(key, new TreeMap<>(redis.opsForHash().entries(key)));
            }
        }
        return state;
    }

    private void judge(int count, boolean withPending) {
        for (int index = 0; index < count; index++) {
            judgeOne(withPending && index % 50 == 7);
        }
    }

    private long judgeOne(boolean pending) {
        long id = nextSubmission.getAndIncrement();
        int user = (int) (id % USERS) + 1;
        int problem = (int) (id / USERS % PROBLEMS) + 1;
        int minute = (int) (id % 240);
        database.result(id, CONTEST, user, problem, minute, pending ? "PENDING" : VERDICTS[(int) (id % VERDICTS.length)]);
        return id;
    }

    private long pending() {
        return database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_result WHERE provisional_result = 'PENDING'", Long.class);
    }

    private long rangesOpened() {
        return database.jdbc.queryForObject("SELECT COUNT(*) FROM scoreboard_sequence_recovery_range", Long.class);
    }

    private long rangesPending() {
        return database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM scoreboard_sequence_recovery_range WHERE status = 'PENDING'", Long.class);
    }

    private ContestScoreboardRecoveryRange firstRange() {
        return database.jdbc.queryForObject(
                "SELECT generation, from_exclusive, through_inclusive FROM scoreboard_sequence_recovery_range"
                        + " ORDER BY generation LIMIT 1",
                (rs, rowNum) -> new ContestScoreboardRecoveryRange(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting until " + what);
            }
            sleep(10);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static long millis(long nanos) {
        return Duration.ofNanos(nanos).toMillis();
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        return registry.find(name).counter() == null ? 0 : registry.find(name).counter().count();
    }

    private static LettuceConnectionFactory connection(int database) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.setDatabase(database);
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /** The JDBC ledger, recording the bounds of every range read so the test can show none was unbounded. */
    private static final class RecordingLedger implements ContestScoreboardSequenceLedger {

        private final ContestScoreboardSequenceLedger delegate;
        final List<List<Long>> rangeQueries = new CopyOnWriteArrayList<>();

        RecordingLedger(ContestScoreboardSequenceLedger delegate) {
            this.delegate = delegate;
        }

        @Override
        public long highestDurableSequence() {
            return delegate.highestDurableSequence();
        }

        @Override
        public Long highestUnsequencedJudgedSubmissionId() {
            return delegate.highestUnsequencedJudgedSubmissionId();
        }

        @Override
        public List<ContestScoreboardSequencedResult> unsequencedJudgedResults(Long afterId, long throughId, int limit) {
            return delegate.unsequencedJudgedResults(afterId, throughId, limit);
        }

        @Override
        public void recordApplied(Map<Long, Long> sequencesBySubmissionId) {
            delegate.recordApplied(sequencesBySubmissionId);
        }

        @Override
        public List<ContestScoreboardRecoveryRange> pendingRanges() {
            return delegate.pendingRanges();
        }

        @Override
        public ContestScoreboardRecoveryRange openRange(long fromExclusive, long throughInclusive) {
            return delegate.openRange(fromExclusive, throughInclusive);
        }

        @Override
        public List<ContestScoreboardSequencedResult> judgedResultsInRange(long fromExclusive, long throughInclusive,
                                                                          int limit) {
            rangeQueries.add(new ArrayList<>(List.of(fromExclusive, throughInclusive)));
            return delegate.judgedResultsInRange(fromExclusive, throughInclusive, limit);
        }

        @Override
        public boolean completeRange(long generation) {
            return delegate.completeRange(generation);
        }
    }
}
