package my.oj.web.contest.submission.judge;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class JdbcContestSubmissionJudgeResultBatchPersistence {

    private static final String RESULT_INSERT_SQL = """
            INSERT IGNORE INTO contest_submission_result (
                submission_id,
                contest_id,
                provisional_result,
                provisional_judged_at,
                final_result,
                final_judged_at,
                result_saved_at
            )
            VALUES (?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP(6))
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcContestSubmissionJudgeResultBatchPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void persistAll(List<ContestSubmissionJudgeResultCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(RESULT_INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ContestSubmissionJudgeResultCommand command = commands.get(index);
                statement.setLong(1, command.submissionId());
                statement.setLong(2, command.contestId());
                statement.setString(3, command.result().name());
                statement.setTimestamp(4, timestamp(command.judgedAt()));
            }

            @Override
            public int getBatchSize() {
                return commands.size();
            }
        });
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
