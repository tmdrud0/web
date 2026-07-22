package my.oj.web.contest.submission.messaging;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestJudgeOutboxStoreTests {

    @Test
    void completeAllUpdatesPublishedAndFailedEventsInOneTransaction() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1}, new int[]{Statement.SUCCESS_NO_INFO});
        ContestJudgeOutboxStore store = new ContestJudgeOutboxStore(jdbcTemplate, transactionManager);
        ContestJudgeOutboxStore.ClaimedEvent published =
                new ContestJudgeOutboxStore.ClaimedEvent(1L, 11L, "published-token");
        ContestJudgeOutboxStore.ClaimedEvent failed =
                new ContestJudgeOutboxStore.ClaimedEvent(2L, 22L, "failed-token");

        ContestJudgeOutboxStore.BatchCompletionResult result = store.completeAll(
                List.of(published),
                List.of(new ContestJudgeOutboxStore.FailedEvent(failed, "publisher nack"))
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), setterCaptor.capture());
        verify(transactionManager).commit(transactionStatus);

        PreparedStatement publishedStatement = mock(PreparedStatement.class);
        setterCaptor.getAllValues().get(0).setValues(publishedStatement, 0);
        verify(publishedStatement).setLong(1, 1L);
        verify(publishedStatement).setString(2, "published-token");

        PreparedStatement failedStatement = mock(PreparedStatement.class);
        setterCaptor.getAllValues().get(1).setValues(failedStatement, 0);
        verify(failedStatement).setString(1, "publisher nack");
        verify(failedStatement).setLong(2, 2L);
        verify(failedStatement).setString(3, "failed-token");

        assertThat(result.publishedApplied()).isEqualTo(1);
        assertThat(result.failedApplied()).isEqualTo(1);
        assertThat(result.staleCount()).isZero();
    }

    @Test
    void completeAllCountsRowsRejectedByClaimTokenAsStale() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{0});
        ContestJudgeOutboxStore store = new ContestJudgeOutboxStore(jdbcTemplate, transactionManager);

        ContestJudgeOutboxStore.BatchCompletionResult result = store.completeAll(
                List.of(new ContestJudgeOutboxStore.ClaimedEvent(1L, 11L, "stale-token")),
                List.of()
        );

        assertThat(result.staleCount()).isEqualTo(1);
        verify(transactionManager).commit(transactionStatus);
    }
}
