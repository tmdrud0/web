package my.oj.web.contest.submission.messaging;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

@Component
public class ContestJudgeOutboxWriter {

    private static final String INSERT_SQL = """
            INSERT INTO contest_judge_outbox (submission_id, status, attempts, created_at, updated_at)
            VALUES (?, 'PENDING', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE submission_id = submission_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public ContestJudgeOutboxWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAll(Collection<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }

        List<Long> ids = submissionIds.stream().filter(id -> id != null).toList();
        if (ids.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setLong(1, ids.get(index));
            }

            @Override
            public int getBatchSize() {
                return ids.size();
            }
        });
    }
}
