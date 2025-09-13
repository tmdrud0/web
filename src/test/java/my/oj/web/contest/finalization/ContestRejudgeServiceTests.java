package my.oj.web.contest.finalization;

import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.judge.Judgement;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestRejudgeServiceTests {

    @Mock private ContestSubmissionService contestSubmissionService;
    @Mock private ContestSubmissionResultRepository resultRepository;
    @Mock private Judgement finalJudgement;
    @Mock private ContestSubmissionBatchExecutor batchExecutor;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks private ContestRejudgeService rejudgeService;

    private ContestSubmission submission1;
    private ContestSubmission submission2;

    @BeforeEach
    void setUp() {
        User user = User.withState(1L, "user", "pass", 0L, new Streak());
        Contest contest = new Contest("Contest");
        Problem problem = Problem.create("P", contest, 1L);
        submission1 = ContestSubmission.create(user, problem, "code1", "hash1", LocalDateTime.now().minusMinutes(5));
        submission2 = ContestSubmission.create(user, problem, "code2", "hash2", LocalDateTime.now().minusMinutes(3));
        ReflectionTestUtils.setField(submission1, "id", 11L);
        ReflectionTestUtils.setField(submission2, "id", 22L);
    }

    @Test
    void rejudgeAcceptedSubmissions_runsThroughMultipleBatches() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(resultRepository.findSubmissionIdsByContestIdAndProvisionalResult(eq(99L), eq(SubmissionResult.ACCEPTED), isNull(), any()))
                .thenReturn(List.of(11L));
        when(resultRepository.findSubmissionIdsByContestIdAndProvisionalResult(eq(99L), eq(SubmissionResult.ACCEPTED), eq(11L), any()))
                .thenReturn(List.of(22L));
        when(resultRepository.findSubmissionIdsByContestIdAndProvisionalResult(eq(99L), eq(SubmissionResult.ACCEPTED), eq(22L), any()))
                .thenReturn(List.of());

        when(contestSubmissionService.findAllByIdsInOrder(List.of(11L))).thenReturn(List.of(submission1));
        when(contestSubmissionService.findAllByIdsInOrder(List.of(22L))).thenReturn(List.of(submission2));
        ContestSubmissionResult result1 = ContestSubmissionResult.pending(submission1);
        ReflectionTestUtils.setField(result1, "id", 11L);
        when(resultRepository.findAllById(List.of(11L))).thenReturn(List.of(result1));
        when(resultRepository.findAllById(List.of(22L))).thenReturn(List.of());

        Submission judged1 = Submission.create(submission1.getUser(), submission1.getProblem(), submission1.getCode(), submission1.getSubmittedTime());
        judged1.setResult(SubmissionResult.ACCEPTED);
        Submission judged2 = Submission.create(submission2.getUser(), submission2.getProblem(), submission2.getCode(), submission2.getSubmittedTime());
        judged2.setResult(SubmissionResult.WRONG_ANSWER);
        when(finalJudgement.judgeSubmission(any(Submission.class))).thenReturn(judged1, judged2);

        doAnswer(invocation -> {
            ContestSubmissionBatchExecutor.BatchLoader loader = invocation.getArgument(2);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<List<Long>> consumer = invocation.getArgument(3);
            ContestSubmissionBatchExecutor executor = new ContestSubmissionBatchExecutor(new NoOpTransactionManager());
            executor.processBatchesNonTransactional(99L, invocation.getArgument(1), loader, consumer);
            return null;
        }).when(batchExecutor).processBatchesNonTransactional(anyLong(), anyInt(), any(), any());

        rejudgeService.rejudgeAcceptedSubmissions(99L);

        ArgumentCaptor<List<Long>> idCaptor = ArgumentCaptor.forClass(List.class);
        verify(contestSubmissionService, times(2)).findAllByIdsInOrder(idCaptor.capture());
        assertThat(idCaptor.getAllValues()).containsExactly(List.of(11L), List.of(22L));

        verify(resultRepository, times(2)).saveAll(anyList());
        verify(finalJudgement, times(2)).judgeSubmission(any(Submission.class));
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // no-op
        }
    }
}












