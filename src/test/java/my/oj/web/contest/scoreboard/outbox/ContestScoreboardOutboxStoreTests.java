package my.oj.web.contest.scoreboard.outbox;

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

class ContestScoreboardOutboxStoreTests {

    @Test
    void claimLoadsPayloadAndMarksTheBatchWithOneLeaseToken() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ResultSet resultSet = mock(ResultSet.class);
        Timestamp contestStart = Timestamp.valueOf(LocalDateTime.of(2026, 3, 10, 12, 0));
        Timestamp submittedAt = Timestamp.valueOf(LocalDateTime.of(2026, 3, 10, 12, 1));
        Timestamp judgedAt = Timestamp.valueOf(LocalDateTime.of(2026, 3, 10, 12, 2));
        when(resultSet.getLong("id")).thenReturn(7L);
        when(resultSet.getLong("contest_submission_id")).thenReturn(1007L);
        when(resultSet.getLong("contest_id")).thenReturn(10L);
        when(resultSet.getLong("problem_id")).thenReturn(20L);
        when(resultSet.getLong("user_id")).thenReturn(30L);
        when(resultSet.getTimestamp("contest_start")).thenReturn(contestStart);
        when(resultSet.getTimestamp("submitted_time")).thenReturn(submittedAt);
        when(resultSet.getTimestamp("judged_at")).thenReturn(judgedAt);
        when(resultSet.getString("result")).thenReturn("ACCEPTED");
        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(-30_000_000L),
                eq(25)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> rowMapper = invocation.getArgument(1);
            return List.of(rowMapper.mapRow(resultSet, 0));
        });
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});
        ContestScoreboardOutboxStore store = new ContestScoreboardOutboxStore(
                jdbcTemplate,
                transactionManager
        );

        List<ContestScoreboardOutboxStore.ClaimedEvent> claimed = store.claim(
                25,
                Duration.ofSeconds(30)
        );

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).eventId()).isEqualTo(7L);
        assertThat(claimed.get(0).payload().contestSubmissionId()).isEqualTo(1007L);
        assertThat(claimed.get(0).payload().result()).isEqualTo(SubmissionResult.ACCEPTED);
        assertThat(claimed.get(0).claimToken()).hasSize(36);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                queryCaptor.capture(),
                any(RowMapper.class),
                eq(-30_000_000L),
                eq(25)
        );
        assertThat(queryCaptor.getValue())
                .contains("status = 'PENDING'")
                .contains("status = 'FAILED'")
                .contains("next_attempt_at <= CURRENT_TIMESTAMP(6)")
                .contains("status = 'PROCESSING'")
                .contains("TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))")
                .contains("FOR UPDATE SKIP LOCKED");

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

    @Test
    void completeAllBatchesSuccessAndFailureWithClaimTokenGuards() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1}, new int[]{Statement.SUCCESS_NO_INFO});
        ContestScoreboardOutboxStore store = new ContestScoreboardOutboxStore(
                jdbcTemplate,
                transactionManager
        );
        ContestScoreboardOutboxStore.ClaimedEvent completed = claimedEvent(1L, "completed-token");
        ContestScoreboardOutboxStore.ClaimedEvent failed = claimedEvent(2L, "failed-token");

        ContestScoreboardOutboxStore.BatchCompletionResult result = store.completeAll(
                List.of(new ContestScoreboardOutboxStore.CompletedEvent(completed, 91L)),
                List.of(new ContestScoreboardOutboxStore.FailedEvent(failed, "redis unavailable"))
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
        ContestScoreboardOutboxStore store = new ContestScoreboardOutboxStore(
                jdbcTemplate,
                transactionManager
        );

        ContestScoreboardOutboxStore.BatchCompletionResult result = store.completeAll(
                List.of(new ContestScoreboardOutboxStore.CompletedEvent(
                        claimedEvent(1L, "stale-token"),
                        91L
                )),
                List.of()
        );

        assertThat(result.staleCount()).isOne();
        verify(transactionManager).commit(transactionStatus);
    }

    private ContestScoreboardOutboxStore.ClaimedEvent claimedEvent(long eventId, String claimToken) {
        return new ContestScoreboardOutboxStore.ClaimedEvent(
                eventId,
                new ContestScoreboardOutboxPayload(
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
