package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "contest.submission.bulk",
        name = "persistence-mode",
        havingValue = "jdbc"
)
public class JdbcContestSubmissionBatchPersistence implements ContestSubmissionBatchPersistence {

    private static final String INSERT_SQL = """
            INSERT INTO contest_submission
                (id, contest_id, problem_id, user_id, submitted_time, code, code_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcContestSubmissionBatchPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertAll(List<ContestSubmission> submissions) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ContestSubmission submission = submissions.get(index);
                statement.setLong(1, submission.getId());
                statement.setLong(2, submission.getContest().getId());
                statement.setLong(3, submission.getProblem().getId());
                statement.setLong(4, submission.getUser().getId());
                statement.setTimestamp(5, Timestamp.valueOf(submission.getSubmittedTime()));
                statement.setString(6, submission.getCode());
                statement.setString(7, submission.getCodeHash());
            }

            @Override
            public int getBatchSize() {
                return submissions.size();
            }
        });
    }
}
