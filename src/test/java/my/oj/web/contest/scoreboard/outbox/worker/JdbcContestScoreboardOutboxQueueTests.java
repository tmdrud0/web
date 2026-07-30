package my.oj.web.contest.scoreboard.outbox.worker;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcContestScoreboardOutboxQueueTests {

    @Test
    void claimLoadsPayloadAndMarksTheBatchWithOneLeaseToken() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        stubEmptyRecoveryLanes(jdbcTemplate);
        // 25 - two recovery lanes of 2 each, neither of which returned anything
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(25)))
                .thenAnswer(invocation -> List.of(mapAcceptedRow(invocation)));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});
        JdbcContestScoreboardOutboxQueue store = new JdbcContestScoreboardOutboxQueue(
                jdbcTemplate,
                transactionManager
        );

        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> claimed = store.claim(
                25,
                Duration.ofSeconds(30)
        );

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).eventId()).isEqualTo(7L);
        assertThat(claimed.get(0).update().contestSubmissionId()).isEqualTo(1007L);
        assertThat(claimed.get(0).update().result()).isEqualTo(SubmissionResult.ACCEPTED);
        assertThat(claimed.get(0).claimToken()).hasSize(36);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(anyString(), setterCaptor.capture());
        PreparedStatement claimStatement = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(claimStatement, 0);
        verify(claimStatement).setString(1, claimed.get(0).claimToken());
        verify(claimStatement).setLong(2, 7L);
        verify(transactionManager).commit(transactionStatus);
    }

    /**
     * Each status is claimed by its own query so that MySQL can read the rows straight from an
     * index. One query with the statuses OR-ed together has to sort the whole eligible set, and
     * falls back to a table scan once the backlog grows.
     */
    @Test
    void claimQueriesEachStatusSeparatelySoNoneOfThemSorts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        stubEmptyRecoveryLanes(jdbcTemplate);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(25))).thenReturn(List.of());
        JdbcContestScoreboardOutboxQueue store = new JdbcContestScoreboardOutboxQueue(
                jdbcTemplate,
                transactionManager
        );

        store.claim(25, Duration.ofSeconds(30));

        ArgumentCaptor<String> expiredLease = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(expiredLease.capture(), any(RowMapper.class), eq(-30_000_000L), eq(2));
        assertThat(expiredLease.getValue())
                .contains("status = 'PROCESSING'")
                .contains("TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))")
                .contains("ORDER BY claimed_at, created_at, id")
                .contains("FOR UPDATE SKIP LOCKED");

        ArgumentCaptor<String> laneQueries = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).query(laneQueries.capture(), any(RowMapper.class), any(Integer.class));
        assertThat(laneQueries.getAllValues().get(0))
                .contains("status = 'FAILED'")
                .contains("next_attempt_at <= CURRENT_TIMESTAMP(6)")
                .contains("ORDER BY next_attempt_at, created_at, id")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(laneQueries.getAllValues().get(1))
                .contains("status = 'PENDING'")
                .contains("ORDER BY created_at, id")
                .contains("FOR UPDATE SKIP LOCKED");

        assertThat(expiredLease.getAllValues())
                .allSatisfy(sql -> assertThat(sql).doesNotContain("COALESCE"));
        assertThat(laneQueries.getAllValues())
                .allSatisfy(sql -> assertThat(sql).doesNotContain("COALESCE"));
    }

    /**
     * A saturated PENDING queue must not starve retries and expired leases, so each recovery
     * lane keeps a share of every batch — and cannot take more than that share either.
     */
    @Test
    void recoveryLanesTakeABoundedShareOfTheBatchAndPendingFillsTheRest() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(-30_000_000L), eq(10)))
                .thenAnswer(invocation -> rows(invocation, 10));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(10)))
                .thenAnswer(invocation -> rows(invocation, 10));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(80)))
                .thenAnswer(invocation -> rows(invocation, 80));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[100]);
        JdbcContestScoreboardOutboxQueue store = new JdbcContestScoreboardOutboxQueue(
                jdbcTemplate,
                transactionManager
        );

        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> claimed = store.claim(
                100,
                Duration.ofSeconds(30)
        );

        // 10 expired leases + 10 retries + 80 pending, never more than the requested batch
        assertThat(claimed).hasSize(100);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(-30_000_000L), eq(10));
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(10));
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(80));
    }

    private void stubEmptyRecoveryLanes(JdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(-30_000_000L), eq(2)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(2)))
                .thenReturn(List.of());
    }

    private List<Object> rows(org.mockito.invocation.InvocationOnMock invocation, int count) throws Exception {
        List<Object> mapped = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            mapped.add(mapAcceptedRow(invocation));
        }
        return mapped;
    }

    private Object mapAcceptedRow(org.mockito.invocation.InvocationOnMock invocation) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(7L);
        when(resultSet.getLong("contest_submission_id")).thenReturn(1007L);
        when(resultSet.getLong("contest_id")).thenReturn(10L);
        when(resultSet.getLong("problem_id")).thenReturn(20L);
        when(resultSet.getLong("user_id")).thenReturn(30L);
        when(resultSet.getTimestamp("contest_start"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 10, 12, 0)));
        when(resultSet.getTimestamp("submitted_time"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 10, 12, 1)));
        when(resultSet.getTimestamp("judged_at"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 10, 12, 2)));
        when(resultSet.getString("result")).thenReturn("ACCEPTED");
        @SuppressWarnings("unchecked")
        RowMapper<Object> rowMapper = invocation.getArgument(1);
        return rowMapper.mapRow(resultSet, 0);
    }

    @Test
    void completeAllBatchesSuccessAndFailureWithClaimTokenGuards() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1}, new int[]{Statement.SUCCESS_NO_INFO});
        JdbcContestScoreboardOutboxQueue store = new JdbcContestScoreboardOutboxQueue(
                jdbcTemplate,
                transactionManager
        );
        JdbcContestScoreboardOutboxQueue.ClaimedEvent completed = claimedEvent(1L, "completed-token");
        JdbcContestScoreboardOutboxQueue.ClaimedEvent failed = claimedEvent(2L, "failed-token");

        JdbcContestScoreboardOutboxQueue.BatchCompletionResult result = store.completeAll(
                List.of(new JdbcContestScoreboardOutboxQueue.CompletedEvent(completed, 91L)),
                List.of(new JdbcContestScoreboardOutboxQueue.FailedEvent(failed, "redis unavailable"))
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), setterCaptor.capture());
        verify(transactionManager).commit(transactionStatus);

        PreparedStatement completedStatement = mock(PreparedStatement.class);
        setterCaptor.getAllValues().get(0).setValues(completedStatement, 0);
        verify(completedStatement).setLong(1, 91L);
        verify(completedStatement).setLong(2, 1L);
        verify(completedStatement).setString(3, "completed-token");

        PreparedStatement failedStatement = mock(PreparedStatement.class);
        setterCaptor.getAllValues().get(1).setValues(failedStatement, 0);
        verify(failedStatement).setString(1, "redis unavailable");
        verify(failedStatement).setLong(2, 2L);
        verify(failedStatement).setString(3, "failed-token");

        assertThat(result.completedApplied()).isEqualTo(1);
        assertThat(result.failedApplied()).isEqualTo(1);
        assertThat(result.staleCount()).isZero();
    }

    @Test
    void completeAllCountsRejectedClaimTokensAsStale() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{0});
        JdbcContestScoreboardOutboxQueue store = new JdbcContestScoreboardOutboxQueue(
                jdbcTemplate,
                transactionManager
        );

        JdbcContestScoreboardOutboxQueue.BatchCompletionResult result = store.completeAll(
                List.of(new JdbcContestScoreboardOutboxQueue.CompletedEvent(
                        claimedEvent(1L, "stale-token"),
                        91L
                )),
                List.of()
        );

        assertThat(result.staleCount()).isOne();
        verify(transactionManager).commit(transactionStatus);
    }

    private JdbcContestScoreboardOutboxQueue.ClaimedEvent claimedEvent(long eventId, String claimToken) {
        return new JdbcContestScoreboardOutboxQueue.ClaimedEvent(
                eventId,
                new ContestScoreboardUpdate(
                        1000L + eventId,
                        10L,
                        20L,
                        30L,
                        LocalDateTime.of(2026, 3, 10, 12, 0),
                        LocalDateTime.of(2026, 3, 10, 12, 1),
                        SubmissionResult.ACCEPTED,
                        LocalDateTime.of(2026, 3, 10, 12, 2)
                ),
                claimToken
        );
    }
}
