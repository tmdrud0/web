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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new JdbcContestSubmissionBatchPersistence.StoredSubmissionRow(
                        40L, 10L, 20L, 30L, "hash"
                )));

        ContestSubmissionBatchInsertResult result = persistence.insertAll(List.of(submission));

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), setterCaptor.capture());
        assertThat(sqlCaptor.getValue()).containsIgnoringCase("INSERT IGNORE INTO contest_submission");
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
        assertThat(result.resolutionFor(40L).duplicate()).isFalse();
        assertThat(result.resolutionFor(40L).submissionId()).isEqualTo(40L);
    }

    @Test
    void classifyTreatsMissingReservedIdWithMatchingDedupKeyAsDuplicate() {
        ContestSubmission submission = submission(40L, "hash");

        ContestSubmissionBatchInsertResult result = JdbcContestSubmissionBatchPersistence.classify(
                List.of(submission),
                List.of(),
                List.of(new JdbcContestSubmissionBatchPersistence.StoredSubmissionRow(
                        99L, 10L, 20L, 30L, "hash"
                ))
        );

        assertThat(result.resolutionFor(40L).duplicate()).isTrue();
        assertThat(result.resolutionFor(40L).submissionId()).isEqualTo(99L);
    }

    @Test
    void classifyFailsWhenInsertIgnoreOmissionHasNoMatchingDuplicate() {
        ContestSubmission submission = submission(40L, "hash");

        assertThatThrownBy(() -> JdbcContestSubmissionBatchPersistence.classify(
                List.of(submission),
                List.of(),
                List.of()
        ))
                .isInstanceOf(ContestSubmissionBatchConsistencyException.class)
                .hasMessageContaining("without a matching duplicate")
                .hasMessageContaining("40");
    }

    @Test
    void classifyFailsWhenReservedIdBelongsToDifferentSubmission() {
        ContestSubmission submission = submission(40L, "hash");

        assertThatThrownBy(() -> JdbcContestSubmissionBatchPersistence.classify(
                List.of(submission),
                List.of(new JdbcContestSubmissionBatchPersistence.StoredSubmissionRow(
                        40L, 10L, 20L, 30L, "different-hash"
                )),
                List.of()
        ))
                .isInstanceOf(ContestSubmissionBatchConsistencyException.class)
                .hasMessageContaining("occupied by a different submission");
    }

    @Test
    void classifyReportsEveryOffendingReservedIdInOneAttempt() {
        ContestSubmission occupied = submission(40L, "hash");
        ContestSubmission omitted = submission(41L, "other-hash");

        assertThatThrownBy(() -> JdbcContestSubmissionBatchPersistence.classify(
                List.of(occupied, omitted),
                List.of(new JdbcContestSubmissionBatchPersistence.StoredSubmissionRow(
                        40L, 10L, 20L, 30L, "different-hash"
                )),
                List.of()
        ))
                .isInstanceOfSatisfying(
                        ContestSubmissionBatchConsistencyException.class,
                        failure -> assertThat(failure.offendingSubmissionIds()).containsExactly(40L, 41L)
                );
    }

    @Test
    void classifyLeavesOffendingIdsEmptyWhenTheBatchRepeatsAReservedId() {
        ContestSubmission first = submission(40L, "hash");
        ContestSubmission repeated = submission(40L, "other-hash");

        assertThatThrownBy(() -> JdbcContestSubmissionBatchPersistence.classify(
                List.of(first, repeated),
                List.of(),
                List.of()
        ))
                .isInstanceOfSatisfying(
                        ContestSubmissionBatchConsistencyException.class,
                        failure -> assertThat(failure.offendingSubmissionIds()).isEmpty()
                );
    }

    private ContestSubmission submission(long submissionId, String codeHash) {
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 10L);
        Problem problem = Problem.create("A", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 20L);
        User user = User.withState(30L, "alice", "pw", 0L, new Streak());
        ContestSubmission submission = ContestSubmission.create(
                contest, user, problem, "print(1)", codeHash, LocalDateTime.now()
        );
        submission.assignId(submissionId);
        return submission;
    }
}
