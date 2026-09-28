package my.oj.web.contest.scoreboard.poll;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** A Flyway-migrated MySQL schema in a container, and the rows the poller reads. */
final class MySqlPollTestDatabase {

    static final LocalDateTime START = LocalDateTime.of(2026, 9, 28, 9, 0);

    final DataSource dataSource;
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager transactionManager;

    MySqlPollTestDatabase(MySQLContainer<?> mysql) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        this.dataSource = source;
        this.jdbc = new JdbcTemplate(source);
        this.transactionManager = new DataSourceTransactionManager(source);
    }

    static MySQLContainer<?> container() {
        return new MySQLContainer<>("mysql:8.0").withDatabaseName("oj_poll").withUsername("oj").withPassword("oj");
    }

    void migrateFromScratch() {
        flyway(null).clean();
        flyway(null).migrate();
    }

    /** Migrates up to and including {@code version}, so a test can seed rows an older schema holds. */
    void migrateFromScratchTo(String version) {
        flyway(null).clean();
        flyway(version).migrate();
    }

    void migrateRest() {
        flyway(null).migrate();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    void contest(long contestId, int users, int problems) {
        jdbc.update("INSERT INTO contest (id, name, start_time, end_time) VALUES (?, ?, ?, ?)",
                contestId, "poll-" + contestId, Timestamp.valueOf(START), Timestamp.valueOf(START.plusHours(5)));
        for (int user = 1; user <= users; user++) {
            jdbc.update("INSERT IGNORE INTO user (id, name, pass) VALUES (?, ?, 'x')", userId(user), "u" + user);
        }
        for (int problem = 1; problem <= problems; problem++) {
            jdbc.update("INSERT INTO problem (id, name, contest_id, contest_num) VALUES (?, ?, ?, ?)",
                    problemId(contestId, problem), "p" + problem, contestId, problem);
        }
    }

    static long userId(int user) {
        return 1000L + user;
    }

    static long problemId(long contestId, int problem) {
        return contestId * 100 + problem;
    }

    /** One contest submission and its result row, as the judge leaves them. */
    void result(long submissionId, long contestId, int user, int problem, int minute, String result) {
        jdbc.update("""
                INSERT INTO contest_submission (id, contest_id, problem_id, user_id, submitted_time, code, code_hash)
                VALUES (?, ?, ?, ?, ?, 'code', ?)
                """, submissionId, contestId, problemId(contestId, problem), userId(user),
                Timestamp.valueOf(START.plusMinutes(minute)), "h" + submissionId);
        jdbc.update("""
                INSERT INTO contest_submission_result (submission_id, contest_id, provisional_result)
                VALUES (?, ?, ?)
                """, submissionId, contestId, result);
    }

    void judge(long submissionId, String result) {
        jdbc.update("UPDATE contest_submission_result SET provisional_result = ? WHERE submission_id = ?",
                result, submissionId);
    }

    Long sequenceOf(long submissionId) {
        return jdbc.queryForObject(
                "SELECT scoreboard_applied_seq FROM contest_submission_result WHERE submission_id = ?",
                Long.class, submissionId);
    }

    long watermark() {
        return jdbc.queryForObject("SELECT highest_durable_seq FROM scoreboard_sequence_watermark WHERE id = 1",
                Long.class);
    }

    long unsequencedJudged() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM contest_submission_result
                WHERE scoreboard_applied_seq IS NULL AND COALESCE(final_result, provisional_result) <> 'PENDING'
                """, Long.class);
    }

    /** Test-only verification query; the production code never groups by sequence. */
    long duplicateSequences() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT scoreboard_applied_seq FROM contest_submission_result
                    WHERE scoreboard_applied_seq IS NOT NULL
                    GROUP BY scoreboard_applied_seq HAVING COUNT(*) > 1) duplicated
                """, Long.class);
    }
}
