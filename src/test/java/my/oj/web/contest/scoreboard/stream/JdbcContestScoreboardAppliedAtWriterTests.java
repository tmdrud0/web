package my.oj.web.contest.scoreboard.stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JdbcContestScoreboardAppliedAtWriterTests {

    @Test
    void writesDistinctSubmissionIdsAsOneSortedJdbcBatch() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcContestScoreboardAppliedAtWriter writer = new JdbcContestScoreboardAppliedAtWriter(jdbcTemplate);

        writer.markApplied(List.of(9L, 2L, 9L, 5L));

        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("scoreboard_applied_at"), setter.capture());
        assertThat(setter.getValue().getBatchSize()).isEqualTo(3);
        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, 0);
        setter.getValue().setValues(statement, 1);
        setter.getValue().setValues(statement, 2);
        verify(statement).setLong(1, 2L);
        verify(statement).setLong(1, 5L);
        verify(statement).setLong(1, 9L);
    }

    /**
     * The sequence statement must not bind the sequence where the timestamp statement binds the id,
     * and must leave the column alone rather than writing a null over a sequence the scoreboard
     * previously recorded for that result.
     */
    @Test
    void writesTheSequenceBesideTheIdAndLeavesItAloneWhenThereIsNone() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcContestScoreboardAppliedAtWriter writer = new JdbcContestScoreboardAppliedAtWriter(jdbcTemplate);

        writer.markApplied(List.of(9L, 5L), Map.of(9L, 41L));

        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(contains("scoreboard_applied_seq"), setter.capture());
        assertThat(setter.getValue().getBatchSize()).isEqualTo(2);

        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, 0);
        setter.getValue().setValues(statement, 1);
        // Ids are sorted, so 5L is row 0 and carries no sequence.
        verify(statement).setNull(1, Types.BIGINT);
        verify(statement).setLong(2, 5L);
        verify(statement).setLong(1, 41L);
        verify(statement).setLong(2, 9L);
    }

    @Test
    void anEmptyBatchIssuesNoStatement() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcContestScoreboardAppliedAtWriter writer = new JdbcContestScoreboardAppliedAtWriter(jdbcTemplate);

        writer.markApplied(List.of());
        writer.markApplied(List.of(), Map.of(1L, 2L));

        verify(jdbcTemplate, org.mockito.Mockito.never())
                .batchUpdate(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(BatchPreparedStatementSetter.class));
    }
}
