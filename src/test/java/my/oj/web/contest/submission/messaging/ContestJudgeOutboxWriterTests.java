package my.oj.web.contest.submission.messaging;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ContestJudgeOutboxWriterTests {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ContestJudgeOutboxWriter writer = new ContestJudgeOutboxWriter(jdbcTemplate);

    @Test
    void enqueueAllUsesOneJdbcBatchAndSkipsNullIds() throws Exception {
        writer.enqueueAll(List.of(11L, 12L));

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(anyString(), setterCaptor.capture());

        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        PreparedStatement statement = mock(PreparedStatement.class);
        assertThat(setter.getBatchSize()).isEqualTo(2);
        setter.setValues(statement, 0);
        setter.setValues(statement, 1);
        verify(statement).setLong(1, 11L);
        verify(statement).setLong(1, 12L);
    }

    @Test
    void enqueueAllDoesNothingForEmptyInput() {
        writer.enqueueAll(List.of());

        verify(jdbcTemplate, never()).batchUpdate(anyString(),
                org.mockito.ArgumentMatchers.any(BatchPreparedStatementSetter.class));
    }
}
