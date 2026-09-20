package my.oj.web.contest.scoreboard.stream;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

@Component
public class JdbcContestScoreboardAppliedAtWriter {

    private static final String MARK_APPLIED_SQL = """
            UPDATE contest_submission_result
            SET scoreboard_applied_at = COALESCE(scoreboard_applied_at, CURRENT_TIMESTAMP(6))
            WHERE submission_id = ?
            """;

    /**
     * {@code scoreboard_applied_seq} is overwritten rather than coalesced, unlike the timestamp: a
     * replayed result must converge on the sequence the scoreboard now holds for it, otherwise it
     * stays above the allocator and is replayed again on every check. A null sequence leaves the
     * column alone instead of erasing it - a result the scoreboard never sequenced carries no
     * information about what an earlier application recorded.
     */
    private static final String MARK_APPLIED_WITH_SEQUENCE_SQL = """
            UPDATE contest_submission_result
            SET scoreboard_applied_at = COALESCE(scoreboard_applied_at, CURRENT_TIMESTAMP(6)),
                scoreboard_applied_seq = COALESCE(?, scoreboard_applied_seq)
            WHERE submission_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcContestScoreboardAppliedAtWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void markApplied(List<Long> submissionIds) {
        markApplied(submissionIds, MARK_APPLIED_SQL, false);
    }

    /**
     * Records the staleness timestamp and the recovery sequence a batch reached the scoreboard
     * under. Ids with no entry in {@code sequences} still get their timestamp written.
     */
    @Transactional
    public void markApplied(List<Long> submissionIds, Map<Long, Long> sequences) {
        Map<Long, Long> safeSequences = sequences == null ? Map.of() : sequences;
        markApplied(submissionIds, MARK_APPLIED_WITH_SEQUENCE_SQL, true, safeSequences);
    }

    private void markApplied(List<Long> submissionIds, String sql, boolean withSequence) {
        markApplied(submissionIds, sql, withSequence, Map.of());
    }

    private void markApplied(List<Long> submissionIds,
                             String sql,
                             boolean withSequence,
                             Map<Long, Long> sequences) {
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
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Long submissionId = orderedIds.get(index);
                if (withSequence) {
                    Long sequence = sequences.get(submissionId);
                    if (sequence == null) {
                        statement.setNull(1, Types.BIGINT);
                    } else {
                        statement.setLong(1, sequence);
                    }
                    statement.setLong(2, submissionId);
                    return;
                }
                statement.setLong(1, submissionId);
            }

            @Override
            public int getBatchSize() {
                return orderedIds.size();
            }
        });
    }
}
