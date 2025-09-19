package my.oj.web.contest.scoreboard;

import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardRebuildServiceTests {

    @Mock
    private ContestScoreboardService scoreboardService;
    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestSubmissionService contestSubmissionService;

    private ContestSubmissionBatchExecutor batchExecutor;
    private ContestScoreboardRebuildService rebuildService;

    @BeforeEach
    void setUp() {
        batchExecutor = new ContestSubmissionBatchExecutor(new NoOpTransactionManager());
        rebuildService = new ContestScoreboardRebuildService(
                scoreboardService,
                resultRepository,
                contestSubmissionService,
                batchExecutor
        );
    }

    @Test
    void rebuildFromContestResults_replaysSubmissionsInChronologicalOrder() {
        Long contestId = 77L;
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", contestId);
        ReflectionTestUtils.setField(contest, "startTime", LocalDateTime.of(2024, 1, 1, 9, 0));

        Problem problem = Problem.create("P1", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 201L);
        User user = User.withState(100L, "user", "pass", 0L, new Streak());

        ContestSubmission submission3 = ContestSubmission.create(user, problem, "code3", "hash3", LocalDateTime.of(2024, 1, 1, 10, 5));
        ReflectionTestUtils.setField(submission3, "id", 3L);
        ContestSubmission submission5 = ContestSubmission.create(user, problem, "code5", "hash5", LocalDateTime.of(2024, 1, 1, 10, 1));
        ReflectionTestUtils.setField(submission5, "id", 5L);

        ContestSubmissionResult result3 = ContestSubmissionResult.pending(submission3);
        result3.recordProvisional(SubmissionResult.WRONG_ANSWER, LocalDateTime.of(2024, 1, 1, 10, 6));
        result3.recordFinal(SubmissionResult.WRONG_ANSWER, LocalDateTime.of(2024, 1, 1, 10, 6));
        ReflectionTestUtils.setField(result3, "id", 3L);

        ContestSubmissionResult result5 = ContestSubmissionResult.pending(submission5);
        result5.recordProvisional(SubmissionResult.ACCEPTED, LocalDateTime.of(2024, 1, 1, 10, 2));
        result5.recordFinal(SubmissionResult.ACCEPTED, LocalDateTime.of(2024, 1, 1, 10, 2));
        ReflectionTestUtils.setField(result5, "id", 5L);

        Map<Long, ContestSubmission> submissionMap = new ConcurrentHashMap<>();
        submissionMap.put(3L, submission3);
        submissionMap.put(5L, submission5);
        Map<Long, ContestSubmissionResult> resultMap = Map.of(3L, result3, 5L, result5);

        when(resultRepository.findSubmissionIdsByContestId(eq(contestId), isNull(), any()))
                .thenReturn(List.of(3L, 5L));
        when(resultRepository.findSubmissionIdsByContestId(eq(contestId), eq(5L), any()))
                .thenReturn(List.of());

        when(contestSubmissionService.findAllByIdsInOrder(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    return ids.stream().map(submissionMap::get).toList();
                });

        when(resultRepository.findAllById(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    return ids.stream()
                            .map(resultMap::get)
                            .toList();
                });

        rebuildService.rebuildFromContestResults(contestId);

        InOrder inOrder = inOrder(scoreboardService);
        inOrder.verify(scoreboardService).reset(contestId);
        inOrder.verify(scoreboardService).recordJudgement(
                eq(5L),
                eq(contestId),
                eq(problem.getId()),
                eq(user.getId()),
                eq(contest.getStartTime()),
                eq(submission5.getSubmittedTime()),
                eq(SubmissionResult.ACCEPTED)
        );
        inOrder.verify(scoreboardService).recordJudgement(
                eq(3L),
                eq(contestId),
                eq(problem.getId()),
                eq(user.getId()),
                eq(contest.getStartTime()),
                eq(submission3.getSubmittedTime()),
                eq(SubmissionResult.WRONG_ANSWER)
        );
        inOrder.verifyNoMoreInteractions();
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