package my.oj.web.contest.submission.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnExpression(
        "'${contest.submission.judge.dispatch-mode:rabbit}' == 'mysql' || "
                + "'${contest.submission.judge.rabbit.publisher.enabled:false}' == 'true'")
class ContestJudgeOutboxStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    ContestJudgeOutboxStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    List<ClaimedEvent> claim(int batchSize, Duration lease) {
        List<ClaimedEvent> claimed = transactionTemplate.execute(status -> {
            List<OutboxRow> rows = jdbcTemplate.query("""
                            SELECT id, submission_id, status
                            FROM contest_judge_outbox
                            WHERE status = 'PENDING'
                               OR (status = 'PUBLISHING' AND claimed_at <
                                   DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL ? MICROSECOND))
                            ORDER BY id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (resultSet, rowNum) -> new OutboxRow(
                            resultSet.getLong("id"),
                            resultSet.getLong("submission_id"),
                            "PUBLISHING".equals(resultSet.getString("status"))
                    ),
                    Math.max(0L, lease.toNanos() / 1_000L),
                    batchSize
            );

            List<ClaimedEvent> events = rows.stream()
                    .map(row -> new ClaimedEvent(
                            row.eventId(), row.submissionId(), UUID.randomUUID().toString(), row.staleReclaim()))
                    .toList();
            if (events.isEmpty()) {
                return events;
            }

            jdbcTemplate.batchUpdate("""
                    UPDATE contest_judge_outbox
                    SET status = 'PUBLISHING', claim_token = ?, claimed_at = CURRENT_TIMESTAMP(6),
                        attempts = attempts + 1, last_error = NULL
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

    BatchCompletionResult completeAll(List<ClaimedEvent> published, List<FailedEvent> failed) {
        List<ClaimedEvent> safePublished = published == null ? List.of() : List.copyOf(published);
        List<FailedEvent> safeFailed = failed == null ? List.of() : List.copyOf(failed);
        if (safePublished.isEmpty() && safeFailed.isEmpty()) {
            return new BatchCompletionResult(0, 0, 0, 0);
        }

        BatchCompletionResult result = transactionTemplate.execute(status -> {
            int[] publishedCounts = safePublished.isEmpty()
                    ? new int[0]
                    : jdbcTemplate.batchUpdate("""
                            UPDATE contest_judge_outbox
                            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6),
                                claim_token = NULL, claimed_at = NULL, last_error = NULL
                            WHERE id = ? AND status = 'PUBLISHING' AND claim_token = ?
                            """, new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index) throws SQLException {
                            ClaimedEvent event = safePublished.get(index);
                            statement.setLong(1, event.eventId());
                            statement.setString(2, event.claimToken());
                        }

                        @Override
                        public int getBatchSize() {
                            return safePublished.size();
                        }
                    });

            int[] failedCounts = safeFailed.isEmpty()
                    ? new int[0]
                    : jdbcTemplate.batchUpdate("""
                            UPDATE contest_judge_outbox
                            SET status = 'PENDING', claim_token = NULL, claimed_at = NULL, last_error = ?
                            WHERE id = ? AND status = 'PUBLISHING' AND claim_token = ?
                            """, new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index) throws SQLException {
                            FailedEvent failedEvent = safeFailed.get(index);
                            statement.setString(1, failedEvent.error());
                            statement.setLong(2, failedEvent.event().eventId());
                            statement.setString(3, failedEvent.event().claimToken());
                        }

                        @Override
                        public int getBatchSize() {
                            return safeFailed.size();
                        }
                    });

            return new BatchCompletionResult(
                    safePublished.size(),
                    appliedCount(publishedCounts),
                    safeFailed.size(),
                    appliedCount(failedCounts)
            );
        });
        return result == null
                ? new BatchCompletionResult(safePublished.size(), 0, safeFailed.size(), 0)
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
        String safeError = error == null ? "Unknown publish failure" : error;
        return safeError.length() <= MAX_ERROR_LENGTH
                ? safeError
                : safeError.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * PUBLISHING is deliberately shared by Rabbit publication and direct MySQL judging: in both
     * cases it means that one token owns a leased unit of work. PUBLISHED means that transport or
     * judging completed. Keeping those meanings avoids a schema/status migration for the
     * experiment while token-fenced updates prevent an expired owner from completing a new lease.
     */
    record ClaimedEvent(long eventId, long submissionId, String claimToken, boolean staleReclaim) {
        ClaimedEvent(long eventId, long submissionId, String claimToken) {
            this(eventId, submissionId, claimToken, false);
        }
    }

    record FailedEvent(ClaimedEvent event, String error) {
        FailedEvent {
            error = safeError(error);
        }
    }

    record BatchCompletionResult(int publishedRequested,
                                 int publishedApplied,
                                 int failedRequested,
                                 int failedApplied) {

        int staleCount() {
            return publishedRequested - publishedApplied + failedRequested - failedApplied;
        }
    }

    private record OutboxRow(long eventId, long submissionId, boolean staleReclaim) {
    }
}
