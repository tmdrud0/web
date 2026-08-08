package my.oj.web.contest.scoreboard.rebuild;

import my.oj.web.contest.Contest;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.contest.scoreboard.stream.JdbcContestScoreboardAppliedAtWriter;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardRebuildServiceTests {

    private static final long CONTEST_ID = 77L;

    @Mock
    private ContestScoreboardApplier scoreboardApplier;
    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestSubmissionService contestSubmissionService;
    @Mock
    private JdbcContestScoreboardAppliedAtWriter appliedAtWriter;

    private ContestScoreboardRebuildService rebuildService;

    private Contest contest;
    private Problem problem;
    private User user;
    private ContestSubmission submission3;
    private ContestSubmission submission5;

    @BeforeEach
    void setUp() {
        rebuildService = new ContestScoreboardRebuildService(
                scoreboardApplier,
                resultRepository,
                contestSubmissionService,
                new ContestSubmissionBatchExecutor(new NoOpTransactionManager()),
                appliedAtWriter
        );

        contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", CONTEST_ID);
        ReflectionTestUtils.setField(contest, "startTime", LocalDateTime.of(2024, 1, 1, 9, 0));

        problem = Problem.create("P1", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 201L);
        user = User.withState(100L, "user", "pass", 0L, new Streak());

        submission3 = ContestSubmission.create(user, problem, "code3", "hash3", LocalDateTime.of(2024, 1, 1, 10, 5));
        ReflectionTestUtils.setField(submission3, "id", 3L);
        submission5 = ContestSubmission.create(user, problem, "code5", "hash5", LocalDateTime.of(2024, 1, 1, 10, 1));
        ReflectionTestUtils.setField(submission5, "id", 5L);
    }

    @Test
    void rebuildFromContestResults_replaysSubmissionsInChronologicalOrder() {
        stubBatches(Map.of(
                3L, judged(submission3, SubmissionResult.WRONG_ANSWER),
                5L, judged(submission5, SubmissionResult.ACCEPTED)
        ));
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> succeed(invocation.getArgument(0)));

        rebuildService.rebuildFromContestResults(CONTEST_ID);

        InOrder order = inOrder(scoreboardApplier);
        order.verify(scoreboardApplier).reset(CONTEST_ID);
        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> captor = requestCaptor();
        order.verify(scoreboardApplier).applyAll(captor.capture());

        assertThat(captor.getValue())
                .extracting(ContestScoreboardApplier.ApplyRequest::update)
                .extracting(
                        ContestScoreboardUpdate::contestSubmissionId,
                        ContestScoreboardUpdate::contestId,
                        ContestScoreboardUpdate::problemId,
                        ContestScoreboardUpdate::userId,
                        ContestScoreboardUpdate::submittedTime,
                        ContestScoreboardUpdate::result
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                5L, CONTEST_ID, problem.getId(), user.getId(),
                                submission5.getSubmittedTime(), SubmissionResult.ACCEPTED),
                        org.assertj.core.groups.Tuple.tuple(
                                3L, CONTEST_ID, problem.getId(), user.getId(),
                                submission3.getSubmittedTime(), SubmissionResult.WRONG_ANSWER)
                );
        assertThat(captor.getValue()).allMatch(request -> request.streamOffset() == null);
        verify(appliedAtWriter).markApplied(List.of(5L, 3L));
    }

    /**
     * An unjudged submission carries no result to replay, and applying it anyway would record
     * the submission as handled - the real judgement would then be skipped for good.
     */
    @Test
    void rebuildFromContestResults_skipsSubmissionsThatHaveNotBeenJudged() {
        stubBatches(Map.of(
                3L, ContestSubmissionResult.pending(submission3),
                5L, judged(submission5, SubmissionResult.ACCEPTED)
        ));
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> succeed(invocation.getArgument(0)));

        rebuildService.rebuildFromContestResults(CONTEST_ID);

        ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> captor = requestCaptor();
        verify(scoreboardApplier).applyAll(captor.capture());
        assertThat(captor.getValue())
                .extracting(request -> request.update().contestSubmissionId())
                .containsExactly(5L);
    }

    @Test
    void rebuildFromContestResults_failsLoudlyWhenAnEventCannotBeApplied() {
        stubBatches(Map.of(5L, judged(submission5, SubmissionResult.ACCEPTED)));
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.failure(
                            request.correlationId(), "wrong Redis key type"))
                    .toList();
        });

        assertThatThrownBy(() -> rebuildService.rebuildFromContestResults(CONTEST_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong Redis key type");
    }

    @Test
    void rebuildFromContestResults_doesNotCallTheApplierWhenThereIsNothingToReplay() {
        when(resultRepository.findSubmissionIdsByContestId(eq(CONTEST_ID), isNull(), any()))
                .thenReturn(List.of());

        rebuildService.rebuildFromContestResults(CONTEST_ID);

        verify(scoreboardApplier).reset(CONTEST_ID);
        verify(scoreboardApplier, never()).applyAll(anyList());
        verify(appliedAtWriter, never()).markApplied(anyList());
    }

    private void stubBatches(Map<Long, ContestSubmissionResult> resultMap) {
        Map<Long, ContestSubmission> submissionMap = new ConcurrentHashMap<>();
        submissionMap.put(3L, submission3);
        submissionMap.put(5L, submission5);
        List<Long> ids = List.copyOf(resultMap.keySet());

        // The follow-up page is left unstubbed: an empty list ends the batch loop.
        when(resultRepository.findSubmissionIdsByContestId(eq(CONTEST_ID), isNull(), any()))
                .thenReturn(ids);
        when(contestSubmissionService.findAllByIdsInOrder(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> batch = invocation.getArgument(0);
                    return batch.stream().map(submissionMap::get).toList();
                });
        when(resultRepository.findAllById(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> batch = invocation.getArgument(0);
                    return batch.stream().map(resultMap::get).filter(java.util.Objects::nonNull).toList();
                });
    }

    private static ContestSubmissionResult judged(ContestSubmission submission, SubmissionResult result) {
        ContestSubmissionResult judged = ContestSubmissionResult.pending(submission);
        ReflectionTestUtils.setField(judged, "id", submission.getId());
        judged.recordProvisional(result, LocalDateTime.of(2024, 1, 1, 10, 30));
        judged.recordFinal(result, LocalDateTime.of(2024, 1, 1, 10, 30));
        return judged;
    }

    private static List<ContestScoreboardApplier.ApplyResult> succeed(
            List<ContestScoreboardApplier.ApplyRequest> requests) {
        return requests.stream()
                .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), null))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ContestScoreboardApplier.ApplyRequest>> requestCaptor() {
        return ArgumentCaptor.forClass(List.class);
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
