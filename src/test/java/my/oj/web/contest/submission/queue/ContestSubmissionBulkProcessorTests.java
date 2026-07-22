package my.oj.web.contest.submission.queue;

import jakarta.persistence.EntityManager;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.messaging.ContestJudgeOutboxWriter;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import my.oj.web.problem.Problem;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ContestSubmissionBulkProcessorTests {

    @Mock
    private EntityManager entityManager;
    @Mock
    private ContestJudgeOutboxWriter judgeOutboxWriter;
    @Mock
    private ContestSubmissionBatchPersistence batchPersistence;

    private ContestSubmissionBulkProcessor processor;
    private Contest contest;
    private Problem problem;
    private User user;

    @BeforeEach
    void setUp() {
        processor = new ContestSubmissionBulkProcessor(
                new NoOpContestSubmissionWriteAmplifier(),
                judgeOutboxWriter,
                batchPersistence
        );
        ReflectionTestUtils.setField(processor, "entityManager", entityManager);

        contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 10L);
        problem = Problem.create("A", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 20L);
        user = User.withState(30L, "alice", "pw", 0L, new Streak());
    }

    @Test
    void process_reusesPendingSubmission_whenChunkContainsDuplicate() {
        ContestSubmissionWriteRequest first = new ContestSubmissionWriteRequest(
                10L,
                20L,
                30L,
                "print(1)",
                "hash",
                LocalDateTime.now(),
                100L
        );
        ContestSubmissionWriteRequest duplicate = new ContestSubmissionWriteRequest(
                10L,
                20L,
                30L,
                "print(1)",
                "hash",
                first.submittedTime().plusSeconds(1),
                101L
        );

        given(entityManager.getReference(Contest.class, 10L)).willReturn(contest);
        given(entityManager.getReference(Problem.class, 20L)).willReturn(problem);
        given(entityManager.getReference(User.class, 30L)).willReturn(user);

        var results = processor.process(List.of(first, duplicate));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).duplicate()).isFalse();
        assertThat(results.get(1).duplicate()).isTrue();
        assertThat(results.get(0).submission().getId()).isEqualTo(100L);
        assertThat(results.get(1).submission().getId()).isEqualTo(100L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<my.oj.web.contest.submission.core.ContestSubmission>> persistCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(batchPersistence).insertAll(persistCaptor.capture());
        assertThat(persistCaptor.getValue()).hasSize(1);
        assertThat(persistCaptor.getValue().get(0).getId()).isEqualTo(100L);
        verify(entityManager).clear();
        verify(judgeOutboxWriter).enqueueAll(List.of(100L));
        verifyNoMoreInteractions(entityManager);
    }

    @Test
    void process_throwsWhenReservedIdMissing() {
        ContestSubmissionWriteRequest request = new ContestSubmissionWriteRequest(
                10L,
                20L,
                30L,
                "print(1)",
                "hash",
                LocalDateTime.now()
        );

        given(entityManager.getReference(Contest.class, 10L)).willReturn(contest);
        given(entityManager.getReference(Problem.class, 20L)).willReturn(problem);
        given(entityManager.getReference(User.class, 30L)).willReturn(user);

        assertThatThrownBy(() -> processor.process(List.of(request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservedSubmissionId");
    }
}
