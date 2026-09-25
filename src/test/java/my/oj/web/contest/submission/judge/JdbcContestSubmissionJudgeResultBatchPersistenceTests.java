package my.oj.web.contest.submission.judge;

import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JdbcContestSubmissionJudgeResultBatchPersistenceTests {

    @Test
    void batchesResultsWithoutWritingTheRemovedScoreboardOutbox() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                new JdbcContestSubmissionJudgeResultBatchPersistence(jdbcTemplate);
        LocalDateTime now = LocalDateTime.now();
        List<ContestSubmissionJudgeResultCommand> commands = List.of(
                command(1L, now),
                command(2L, now.plusNanos(1_000))
        );

        persistence.persistAll(commands);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate, times(1)).batchUpdate(sqlCaptor.capture(), setterCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("contest_submission_result")
                .contains("result_saved_at")
                .contains("judge_started_at")
                .contains("CURRENT_TIMESTAMP(6)")
                .doesNotContain("contest_submission_outbox");
        assertThat(setterCaptor.getValue().getBatchSize()).isEqualTo(2);

        PreparedStatement resultStatement = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(resultStatement, 0);
        verify(resultStatement).setLong(1, 1L);
        verify(resultStatement).setString(3, SubmissionResult.PARTIAL_ACCEPTED.name());
        verify(resultStatement).setTimestamp(5, null);
    }

    @Test
    void writesTheJudgeStartInstantWhenTheCommandCarriesOne() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                new JdbcContestSubmissionJudgeResultBatchPersistence(jdbcTemplate);
        LocalDateTime judgedAt = LocalDateTime.of(2026, 9, 25, 12, 0, 0, 500_000_000);
        LocalDateTime startedAt = judgedAt.minusNanos(50_000_000);
        ContestSubmissionJudgeResultCommand command = new ContestSubmissionJudgeResultCommand(
                7L, 10L, 20L, 30L, judgedAt.minusHours(1), judgedAt.minusSeconds(1),
                SubmissionResult.PARTIAL_ACCEPTED, judgedAt, startedAt);

        persistence.persistAll(List.of(command));

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(org.mockito.ArgumentMatchers.anyString(), setterCaptor.capture());
        PreparedStatement statement = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(statement, 0);
        verify(statement).setTimestamp(4, java.sql.Timestamp.valueOf(judgedAt));
        verify(statement).setTimestamp(5, java.sql.Timestamp.valueOf(startedAt));
    }

    private static ContestSubmissionJudgeResultCommand command(Long submissionId, LocalDateTime judgedAt) {
        return new ContestSubmissionJudgeResultCommand(
                submissionId,
                10L,
                20L,
                30L,
                judgedAt.minusHours(1),
                judgedAt.minusMinutes(1),
                SubmissionResult.PARTIAL_ACCEPTED,
                judgedAt
        );
    }
}
