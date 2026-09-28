package my.oj.web.contest.scoreboard.poll;

import my.oj.web.submission.SubmissionResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link ContestScoreboardSequenceLedger} over JDBC.
 *
 * <p>Every write runs in its own {@code REQUIRES_NEW} transaction and never around a Redis call: the
 * Redis write has already happened and cannot be rolled back, so what these transactions commit is
 * evidence about it.</p>
 *
 * <p>The judged filter is {@code COALESCE(final_result, provisional_result) <> 'PENDING'} everywhere: the
 * poll script refuses {@code PENDING}, because the processed set would otherwise swallow the real result.</p>
 */
public class JdbcContestScoreboardSequenceLedger implements ContestScoreboardSequenceLedger {

    private static final String PENDING = SubmissionResult.PENDING.name();

    private static final String SELECT_RESULT = """
            SELECT csr.submission_id,
                   csr.scoreboard_applied_seq,
                   csr.contest_id,
                   cs.problem_id,
                   cs.user_id,
                   c.start_time,
                   cs.submitted_time,
                   COALESCE(csr.final_result, csr.provisional_result) AS result
            FROM contest_submission_result csr
            JOIN contest_submission cs ON cs.id = csr.submission_id
            JOIN contest c ON c.id = csr.contest_id
            """;

    private static final String HIGHEST_UNSEQUENCED_SQL = """
            SELECT MAX(submission_id)
            FROM contest_submission_result
            WHERE scoreboard_applied_seq IS NULL
              AND COALESCE(final_result, provisional_result) <> ?
            """;

    private static final String UNSEQUENCED_SQL = SELECT_RESULT + """
            WHERE csr.scoreboard_applied_seq IS NULL
              AND COALESCE(csr.final_result, csr.provisional_result) <> ?
              AND csr.submission_id > ?
              AND csr.submission_id <= ?
            ORDER BY csr.submission_id
            LIMIT ?
            """;

    private static final String IN_RANGE_SQL = SELECT_RESULT + """
            WHERE csr.scoreboard_applied_seq > ?
              AND csr.scoreboard_applied_seq <= ?
              AND COALESCE(csr.final_result, csr.provisional_result) <> ?
            ORDER BY csr.scoreboard_applied_seq, csr.submission_id
            LIMIT ?
            """;

    private static final String MARK_SQL = """
            UPDATE contest_submission_result
            SET scoreboard_applied_at = COALESCE(scoreboard_applied_at, CURRENT_TIMESTAMP(6)),
                scoreboard_applied_seq = ?
            WHERE submission_id = ?
            """;

    private static final String RAISE_WATERMARK_SQL = """
            UPDATE scoreboard_sequence_watermark
            SET highest_durable_seq = GREATEST(highest_durable_seq, ?),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = 1
            """;

    private static final String WATERMARK_SQL =
            "SELECT highest_durable_seq FROM scoreboard_sequence_watermark WHERE id = 1";

    private static final String RANGE_COLUMNS = "SELECT generation, from_exclusive, through_inclusive"
            + " FROM scoreboard_sequence_recovery_range";

    private static final RowMapper<ContestScoreboardSequencedResult> RESULT_MAPPER = (rs, rowNum) -> {
        long sequence = rs.getLong("scoreboard_applied_seq");
        Long appliedSequence = rs.wasNull() ? null : sequence;
        Timestamp start = rs.getTimestamp("start_time");
        Timestamp submitted = rs.getTimestamp("submitted_time");
        return new ContestScoreboardSequencedResult(
                rs.getLong("submission_id"),
                appliedSequence,
                rs.getLong("contest_id"),
                rs.getLong("problem_id"),
                rs.getLong("user_id"),
                start == null ? null : start.toLocalDateTime(),
                submitted == null ? null : submitted.toLocalDateTime(),
                SubmissionResult.valueOf(rs.getString("result"))
        );
    };

    private static final RowMapper<ContestScoreboardRecoveryRange> RANGE_MAPPER = (rs, rowNum) ->
            new ContestScoreboardRecoveryRange(
                    rs.getLong("generation"), rs.getLong("from_exclusive"), rs.getLong("through_inclusive"));

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcContestScoreboardSequenceLedger(JdbcTemplate jdbcTemplate,
                                               PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public long highestDurableSequence() {
        List<Long> values = jdbcTemplate.queryForList(WATERMARK_SQL, Long.class);
        if (values.isEmpty() || values.get(0) == null) {
            throw new IllegalStateException("scoreboard_sequence_watermark has no row; migration V19 did not run");
        }
        return values.get(0);
    }

    @Override
    public Long highestUnsequencedJudgedSubmissionId() {
        return jdbcTemplate.queryForObject(HIGHEST_UNSEQUENCED_SQL, Long.class, PENDING);
    }

    @Override
    public List<ContestScoreboardSequencedResult> unsequencedJudgedResults(Long afterId, long throughId, int limit) {
        return jdbcTemplate.query(UNSEQUENCED_SQL, RESULT_MAPPER,
                PENDING, afterId == null ? Long.MIN_VALUE : afterId, throughId, limit);
    }

    /**
     * Markers first, then the watermark, then the marker row counts - so that a result whose row did not
     * take the marker fails the transaction after the watermark moved, and takes the watermark back with it.
     */
    @Override
    public void recordApplied(Map<Long, Long> sequencesBySubmissionId) {
        if (sequencesBySubmissionId == null || sequencesBySubmissionId.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(sequencesBySubmissionId.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        long batchMax = entries.stream().map(Map.Entry::getValue).max(Comparator.naturalOrder()).orElseThrow();
        transactionTemplate.executeWithoutResult(status -> {
            int[][] counts = jdbcTemplate.batchUpdate(MARK_SQL, entries, entries.size(), (statement, entry) -> {
                statement.setLong(1, entry.getValue());
                statement.setLong(2, entry.getKey());
            });
            if (jdbcTemplate.update(RAISE_WATERMARK_SQL, batchMax) != 1) {
                throw new IllegalStateException("scoreboard_sequence_watermark has no row to raise");
            }
            for (int[] chunk : counts) {
                for (int count : chunk) {
                    // SUCCESS_NO_INFO (-2) is what a rewritten batch reports; only a definite 0 is a miss.
                    if (count == 0) {
                        throw new IllegalStateException("A scoreboard sequence marker matched no result row;"
                                + " the batch and its watermark are rolled back");
                    }
                }
            }
        });
    }

    @Override
    public List<ContestScoreboardRecoveryRange> pendingRanges() {
        return jdbcTemplate.query(RANGE_COLUMNS + " WHERE status = 'PENDING' ORDER BY generation", RANGE_MAPPER);
    }

    @Override
    public ContestScoreboardRecoveryRange openRange(long fromExclusive, long throughInclusive) {
        return transactionTemplate.execute(status -> {
            List<ContestScoreboardRecoveryRange> existing = jdbcTemplate.query(
                    RANGE_COLUMNS + " WHERE status = 'PENDING' AND from_exclusive = ? AND through_inclusive = ?"
                            + " ORDER BY generation LIMIT 1",
                    RANGE_MAPPER, fromExclusive, throughInclusive);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            KeyHolder keys = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO scoreboard_sequence_recovery_range"
                                + " (from_exclusive, through_inclusive, status, created_at)"
                                + " VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP(6))",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, fromExclusive);
                statement.setLong(2, throughInclusive);
                return statement;
            }, keys);
            Number generation = keys.getKey();
            if (generation == null) {
                throw new IllegalStateException("The recovery range insert returned no generation");
            }
            return new ContestScoreboardRecoveryRange(generation.longValue(), fromExclusive, throughInclusive);
        });
    }

    @Override
    public List<ContestScoreboardSequencedResult> judgedResultsInRange(long fromExclusive,
                                                                      long throughInclusive,
                                                                      int limit) {
        return jdbcTemplate.query(IN_RANGE_SQL, RESULT_MAPPER, fromExclusive, throughInclusive, PENDING, limit);
    }

    @Override
    public boolean completeRange(long generation) {
        Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update(
                "UPDATE scoreboard_sequence_recovery_range"
                        + " SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP(6)"
                        + " WHERE generation = ? AND status = 'PENDING'",
                generation));
        return updated != null && updated == 1;
    }
}
