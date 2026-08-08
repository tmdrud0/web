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

    /**
     * {@code due_at} drives which rows the scoreboard worker claims and is compared against the
     * database clock, so every writer stamps it with {@code CURRENT_TIMESTAMP(6)} rather than a
     * JVM timestamp - otherwise a JVM in a different time zone makes rows claimable too early or
     * never at all.
     */
    private static final String OUTBOX_INSERT_SQL = """
            INSERT IGNORE INTO contest_submission_outbox (
                contest_submission_id,
                contest_id,
                problem_id,
                user_id,
                contest_start,
                submitted_time,
                judged_at,
                result,
                status,
                created_at,
                due_at,
                processed_at,
                last_error_message,
                redis_seq
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP(6), NULL, NULL, NULL)
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

        LocalDateTime createdAt = LocalDateTime.now();
        jdbcTemplate.batchUpdate(OUTBOX_INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ContestSubmissionJudgeResultCommand command = commands.get(index);
                statement.setLong(1, command.submissionId());
                statement.setLong(2, command.contestId());
                statement.setLong(3, command.problemId());
                statement.setLong(4, command.userId());
                statement.setTimestamp(5, timestamp(command.contestStart()));
                statement.setTimestamp(6, timestamp(command.submittedTime()));
                statement.setTimestamp(7, timestamp(command.judgedAt()));
                statement.setString(8, command.result().name());
                statement.setTimestamp(9, timestamp(createdAt));
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
