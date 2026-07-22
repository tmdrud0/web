package my.oj.web.perf;

import jakarta.annotation.PostConstruct;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.queue.ContestSubmissionWriteAmplifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("perf-triple-writes")
public class PerfTripleContestSubmissionWriteAmplifier implements ContestSubmissionWriteAmplifier {

    private final JdbcTemplate jdbcTemplate;
    private final int extraInsertCount;

    public PerfTripleContestSubmissionWriteAmplifier(JdbcTemplate jdbcTemplate,
                                                     @Value("${contest.submission.perf-extra-insert-count:2}") int extraInsertCount) {
        this.jdbcTemplate = jdbcTemplate;
        this.extraInsertCount = Math.max(0, extraInsertCount);
    }

    @PostConstruct
    void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contest_submission_perf_extra (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    submission_id BIGINT NOT NULL,
                    write_seq INT NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_submission_perf_extra_submission_id (submission_id)
                ) ENGINE=InnoDB
                """);
    }

    @Override
    public void amplify(List<ContestSubmission> submissions) {
        if (extraInsertCount == 0 || submissions == null || submissions.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO contest_submission_perf_extra (submission_id, write_seq, created_at)
                VALUES (?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                        ContestSubmission submission = submissions.get(i / extraInsertCount);
                        int writeSeq = (i % extraInsertCount) + 1;
                        ps.setLong(1, submission.getId());
                        ps.setInt(2, writeSeq);
                        ps.setTimestamp(3, Timestamp.valueOf(now));
                    }

                    @Override
                    public int getBatchSize() {
                        return submissions.size() * extraInsertCount;
                    }
                }
        );
    }
}
