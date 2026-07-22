package my.oj.web.contest.submission.queue;

import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.problem.Problem;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JdbcContestSubmissionBatchPersistenceTests {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PreparedStatement statement;

    @Test
    void insertAllMapsSubmissionColumnsIntoOneJdbcBatch() throws Exception {
        LocalDateTime submittedTime = LocalDateTime.of(2026, 7, 18, 12, 30, 15);
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 10L);
        Problem problem = Problem.create("A", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 20L);
        User user = User.withState(30L, "alice", "pw", 0L, new Streak());
        ContestSubmission submission = ContestSubmission.create(
                contest, user, problem, "print(1)", "hash", submittedTime
        );
        submission.assignId(40L);
        JdbcContestSubmissionBatchPersistence persistence =
                new JdbcContestSubmissionBatchPersistence(jdbcTemplate);

        persistence.insertAll(List.of(submission));

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(anyString(), setterCaptor.capture());
        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertThat(setter.getBatchSize()).isEqualTo(1);

        setter.setValues(statement, 0);
        verify(statement).setLong(1, 40L);
        verify(statement).setLong(2, 10L);
        verify(statement).setLong(3, 20L);
        verify(statement).setLong(4, 30L);
        verify(statement).setTimestamp(5, Timestamp.valueOf(submittedTime));
        verify(statement).setString(6, "print(1)");
        verify(statement).setString(7, "hash");
    }
}
