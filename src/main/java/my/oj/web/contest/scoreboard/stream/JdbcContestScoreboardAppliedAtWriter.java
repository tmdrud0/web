package my.oj.web.contest.scoreboard.stream;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Component
public class JdbcContestScoreboardAppliedAtWriter {

    private static final String MARK_APPLIED_SQL = """
            UPDATE contest_submission_result
            SET scoreboard_applied_at = COALESCE(scoreboard_applied_at, CURRENT_TIMESTAMP(6))
            WHERE submission_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcContestScoreboardAppliedAtWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void markApplied(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }
        List<Long> orderedIds = submissionIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (orderedIds.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(MARK_APPLIED_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setLong(1, orderedIds.get(index));
            }

            @Override
            public int getBatchSize() {
                return orderedIds.size();
            }
        });
    }
}
