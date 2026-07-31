package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
class JdbcContestScoreboardOutboxQueue {

    private static final int MAX_ERROR_LENGTH = 500;

    /**
     * Retry delay in seconds: 1, 2, 4, ... capped at 300. {@code attempts} is already incremented
     * by {@link #claim}, so the first failure waits one second.
     */
    private static final String RETRY_BACKOFF =
            "TIMESTAMPADD(SECOND, LEAST(300, POW(2, LEAST(GREATEST(attempts - 1, 0), 9))), CURRENT_TIMESTAMP(6))";

    /**
     * A failed row becomes claimable again exactly when its backoff elapses, so {@code due_at} and
     * {@code next_attempt_at} are the same instant - the latter is kept for observability.
     */
    private static final String FAIL_SQL = """
            UPDATE contest_submission_outbox
            SET status = 'FAILED', claim_token = NULL, claimed_at = NULL, processed_at = NULL,
                next_attempt_at = %1$s,
                due_at = %1$s,
                last_error_message = ?
            WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
            """.formatted(RETRY_BACKOFF);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    JdbcContestScoreboardOutboxQueue(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // REPEATABLE READ takes gap locks while the claim query walks the due_at range. Several
        // workers can then deadlock while moving their claimed rows out of that range. Claiming
        // only needs row locks because SKIP LOCKED and the claim token provide the ownership
        // guarantees.
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    List<ClaimedEvent> claim(int batchSize, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("Scoreboard outbox claim lease must be positive");
        }
        long leaseMicros = Math.max(1L, lease.toNanos() / 1_000L);
        List<ClaimedEvent> claimed = transactionTemplate.execute(status -> {
            List<OutboxRow> rows = jdbcTemplate.query("""
                            SELECT id, contest_submission_id, contest_id, problem_id, user_id,
                                   contest_start, submitted_time, result, judged_at
                            FROM contest_submission_outbox
                            WHERE due_at <= CURRENT_TIMESTAMP(6)
                            ORDER BY due_at, id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (resultSet, rowNum) -> new OutboxRow(
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
                    ),
                    Math.max(1, batchSize)
            );

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
                        due_at = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                        attempts = attempts + 1, next_attempt_at = NULL, last_error_message = NULL
                    WHERE id = ?
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    ClaimedEvent event = events.get(index);
                    statement.setString(1, event.claimToken());
                    statement.setLong(2, leaseMicros);
                    statement.setLong(3, event.eventId());
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
                                claim_token = NULL, claimed_at = NULL, due_at = NULL,
                                next_attempt_at = NULL, last_error_message = NULL
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
                    : jdbcTemplate.batchUpdate(FAIL_SQL, new BatchPreparedStatementSetter() {
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
