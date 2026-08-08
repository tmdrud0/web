package my.oj.web.contest.scoreboard.stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.List;

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
}
