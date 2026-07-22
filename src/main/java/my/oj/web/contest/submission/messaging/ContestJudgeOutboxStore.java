package my.oj.web.contest.submission.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "contest.submission.judge.rabbit.publisher", name = "enabled", havingValue = "true")
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
            Instant staleBefore = Instant.now().minus(lease);
            List<OutboxRow> rows = jdbcTemplate.query("""
                            SELECT id, submission_id
                            FROM contest_judge_outbox
                            WHERE status = 'PENDING'
                               OR (status = 'PUBLISHING' AND claimed_at < ?)
                            ORDER BY id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (resultSet, rowNum) -> new OutboxRow(
                            resultSet.getLong("id"),
                            resultSet.getLong("submission_id")
                    ),
                    Timestamp.from(staleBefore),
                    batchSize
            );

            List<ClaimedEvent> events = rows.stream()
                    .map(row -> new ClaimedEvent(row.eventId(), row.submissionId(), UUID.randomUUID().toString()))
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

    record ClaimedEvent(long eventId, long submissionId, String claimToken) {
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

    private record OutboxRow(long eventId, long submissionId) {
    }
}
