package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
class JdbcContestScoreboardOutboxQueue {

    private static final int MAX_ERROR_LENGTH = 500;

    /** Largest share of one batch each recovery lane may take, as a fraction of the batch size. */
    private static final int RECOVERY_LANE_DIVISOR = 10;

    private static final String CLAIMABLE_COLUMNS = """
            SELECT id, contest_submission_id, contest_id, problem_id, user_id,
                   contest_start, submitted_time, result, judged_at
            FROM contest_submission_outbox
            """;

    /*
     * One query per status. A single query with the three statuses OR-ed together cannot read
     * rows in the order it returns them: no index covers the union, so MySQL sorts the whole
     * eligible set to take one batch, and once the backlog grows it drops the index and scans
     * the table. Each query below matches an index end to end and stops at its LIMIT.
     */

    private static final String CLAIM_PENDING = CLAIMABLE_COLUMNS + """
            WHERE status = 'PENDING'
            ORDER BY created_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String CLAIM_RETRYABLE = CLAIMABLE_COLUMNS + """
            WHERE status = 'FAILED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(6))
            ORDER BY next_attempt_at, created_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String CLAIM_EXPIRED_LEASE = CLAIMABLE_COLUMNS + """
            WHERE status = 'PROCESSING'
              AND (claimed_at IS NULL OR claimed_at < TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)))
            ORDER BY claimed_at, created_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final RowMapper<OutboxRow> OUTBOX_ROW_MAPPER = (resultSet, rowNum) -> new OutboxRow(
            resultSet.getLong("id"),
            new ContestScoreboardUpdate(
                    resultSet.getLong("contest_submission_id"),
                    resultSet.getLong("contest_id"),
                    resultSet.getLong("problem_id"),
                    resultSet.getLong("user_id"),
                    resultSet.getTimestamp("contest_start") == null
                            ? null
                            : resultSet.getTimestamp("contest_start").toLocalDateTime(),
                    resultSet.getTimestamp("submitted_time").toLocalDateTime(),
                    SubmissionResult.valueOf(resultSet.getString("result")),
                    resultSet.getTimestamp("judged_at") == null
                            ? null
                            : resultSet.getTimestamp("judged_at").toLocalDateTime()
            )
    );

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    JdbcContestScoreboardOutboxQueue(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REPEATABLE READ takes gap locks around every range a locking read walks. Claiming
        // reads three index ranges and then moves rows between them, so two workers can hold
        // a gap the other one's UPDATE has to insert into, and deadlock. READ COMMITTED locks
        // the matched rows only. Nothing here needs repeatable reads: a row is claimed by the
        // lease token it carries, and SKIP LOCKED already keeps workers off each other's rows.
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    List<ClaimedEvent> claim(int batchSize, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("Scoreboard outbox claim lease must be positive");
        }
        long leaseMicros = Math.max(1L, lease.toNanos() / 1_000L);
        int limit = Math.max(1, batchSize);
        List<ClaimedEvent> claimed = transactionTemplate.execute(status -> {
            List<OutboxRow> rows = selectClaimable(limit, leaseMicros);

            String claimToken = UUID.randomUUID().toString();
            List<ClaimedEvent> events = rows.stream()
                    .map(row -> new ClaimedEvent(row.eventId(), row.update(), claimToken))
                    .toList();
            if (events.isEmpty()) {
                return events;
            }

            jdbcTemplate.batchUpdate("""
                    UPDATE contest_submission_outbox
                    SET status = 'PROCESSING', claim_token = ?, claimed_at = CURRENT_TIMESTAMP(6),
                        attempts = attempts + 1, next_attempt_at = NULL, last_error_message = NULL
                    WHERE id = ?
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    ClaimedEvent event = events.get(index);
                    statement.setString(1, event.claimToken());
                    statement.setLong(2, event.eventId());
                }

                @Override
                public int getBatchSize() {
                    return events.size();
                }
            });
            return events;
        });
        return claimed == null ? List.of() : claimed;
    }

    /**
     * Reads up to {@code limit} claimable rows, taking each status from its own query.
     *
     * <p>The two recovery lanes run first but are capped at a share of the batch, so a saturated
     * PENDING queue cannot starve retries and expired leases, and a pile of permanently failing
     * rows cannot starve fresh work either. Which status a batch mixes no longer affects the
     * scoreboard, so this is purely a fairness choice.
     */
    private List<OutboxRow> selectClaimable(int limit, long leaseMicros) {
        int recoveryLane = Math.max(1, limit / RECOVERY_LANE_DIVISOR);
        List<OutboxRow> rows = new ArrayList<>(limit);
        appendClaimable(rows, CLAIM_EXPIRED_LEASE, Math.min(recoveryLane, limit), -leaseMicros);
        appendClaimable(rows, CLAIM_RETRYABLE, Math.min(recoveryLane, limit - rows.size()));
        appendClaimable(rows, CLAIM_PENDING, limit - rows.size());
        return rows;
    }

    private void appendClaimable(List<OutboxRow> rows, String sql, int laneLimit, Object... leadingArguments) {
        if (laneLimit <= 0) {
            return;
        }
        Object[] arguments = Arrays.copyOf(leadingArguments, leadingArguments.length + 1);
        arguments[leadingArguments.length] = laneLimit;
        rows.addAll(jdbcTemplate.query(sql, OUTBOX_ROW_MAPPER, arguments));
    }

    BatchCompletionResult completeAll(List<CompletedEvent> completed, List<FailedEvent> failed) {
        List<CompletedEvent> safeCompleted = completed == null ? List.of() : List.copyOf(completed);
        List<FailedEvent> safeFailed = failed == null ? List.of() : List.copyOf(failed);
        if (safeCompleted.isEmpty() && safeFailed.isEmpty()) {
            return new BatchCompletionResult(0, 0, 0, 0);
        }

        BatchCompletionResult result = transactionTemplate.execute(status -> {
            int[] completedCounts = safeCompleted.isEmpty()
                    ? new int[0]
                    : jdbcTemplate.batchUpdate("""
                            UPDATE contest_submission_outbox
                            SET status = 'COMPLETED', redis_seq = ?, processed_at = CURRENT_TIMESTAMP(6),
                                claim_token = NULL, claimed_at = NULL, next_attempt_at = NULL,
                                last_error_message = NULL
                            WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                            """, new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index) throws SQLException {
                            CompletedEvent event = safeCompleted.get(index);
                            if (event.redisSequence() == null) {
                                statement.setNull(1, java.sql.Types.BIGINT);
                            } else {
                                statement.setLong(1, event.redisSequence());
                            }
                            statement.setLong(2, event.event().eventId());
                            statement.setString(3, event.event().claimToken());
                        }

                        @Override
                        public int getBatchSize() {
                            return safeCompleted.size();
                        }
                    });

            int[] failedCounts = safeFailed.isEmpty()
                    ? new int[0]
                    : jdbcTemplate.batchUpdate("""
                            UPDATE contest_submission_outbox
                            SET status = 'FAILED', claim_token = NULL, claimed_at = NULL, processed_at = NULL,
                                next_attempt_at = TIMESTAMPADD(
                                    SECOND,
                                    LEAST(300, POW(2, LEAST(GREATEST(attempts - 1, 0), 9))),
                                    CURRENT_TIMESTAMP(6)
                                ),
                                last_error_message = ?
                            WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                            """, new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index) throws SQLException {
                            FailedEvent event = safeFailed.get(index);
                            statement.setString(1, event.error());
                            statement.setLong(2, event.event().eventId());
                            statement.setString(3, event.event().claimToken());
                        }

                        @Override
                        public int getBatchSize() {
                            return safeFailed.size();
                        }
                    });

            return new BatchCompletionResult(
                    safeCompleted.size(),
                    appliedCount(completedCounts),
                    safeFailed.size(),
                    appliedCount(failedCounts)
            );
        });
        return result == null
                ? new BatchCompletionResult(safeCompleted.size(), 0, safeFailed.size(), 0)
                : result;
    }

    private static int appliedCount(int[] updateCounts) {
        int applied = 0;
        for (int updateCount : updateCounts) {
            if (updateCount > 0 || updateCount == Statement.SUCCESS_NO_INFO) {
                applied++;
            }
        }
        return applied;
    }

    private static String safeError(String error) {
        String safeError = error == null ? "Unknown scoreboard apply failure" : error;
        return safeError.length() <= MAX_ERROR_LENGTH
                ? safeError
                : safeError.substring(0, MAX_ERROR_LENGTH);
    }

    record ClaimedEvent(long eventId, ContestScoreboardUpdate update, String claimToken) {
    }

    record CompletedEvent(ClaimedEvent event, Long redisSequence) {
    }

    record FailedEvent(ClaimedEvent event, String error) {
        FailedEvent {
            error = safeError(error);
        }
    }

    record BatchCompletionResult(int completedRequested,
                                 int completedApplied,
                                 int failedRequested,
                                 int failedApplied) {

        int staleCount() {
            return completedRequested - completedApplied + failedRequested - failedApplied;
        }
    }

    private record OutboxRow(long eventId, ContestScoreboardUpdate update) {
    }
}
