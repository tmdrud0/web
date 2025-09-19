package my.oj.web.contest.finalization;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.finalization.ContestFinalScoreStatus;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.event.normal.NormalSubmissionResultService;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContestFinalizationServiceTests {

    @Mock
    private ContestFinalScoreService finalScoreService;
    @Mock
    private ContestScoreboardService scoreboardService;
    @Mock
    private ContestScoreboardOutboxRepository outboxRepository;
    @Mock
    private ContestRejudgeService rejudgeService;
    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestSubmissionService contestSubmissionService;
    @Mock
    private SubmissionRepository submissionRepository;
    @Mock
    private NormalSubmissionResultService normalSubmissionResultService;
    @Mock
    private ContestSubmissionBatchExecutor batchExecutor;

    @InjectMocks
    private ContestFinalizationService contestFinalizationService;

    private ContestSubmission submission1;
    private ContestSubmission submission2;
    private ContestSubmissionResult result1;
    private ContestSubmissionResult result2;

    @BeforeEach
    void setUp() {
        User user = User.withState(1L, "u", "p", 0L, new Streak());
        my.oj.web.contest.Contest contest = new my.oj.web.contest.Contest("Final");
        Problem problem = Problem.create("P", contest, 1L);
        submission1 = ContestSubmission.create(user, problem, "codeA", "hashA", LocalDateTime.now().minusMinutes(10));
        submission2 = ContestSubmission.create(user, problem, "codeB", "hashB", LocalDateTime.now().minusMinutes(5));
        ReflectionTestUtils.setField(submission1, "id", 1L);
        ReflectionTestUtils.setField(submission2, "id", 2L);

        result1 = ContestSubmissionResult.pending(submission1);
        ReflectionTestUtils.setField(result1, "id", submission1.getId());
        result1.recordProvisional(SubmissionResult.ACCEPTED, LocalDateTime.now().minusMinutes(9));
        result1.recordFinal(SubmissionResult.ACCEPTED, LocalDateTime.now().minusMinutes(9));

        result2 = ContestSubmissionResult.pending(submission2);
        ReflectionTestUtils.setField(result2, "id", submission2.getId());
        result2.recordProvisional(SubmissionResult.WRONG_ANSWER, LocalDateTime.now().minusMinutes(4));
    }

    @Test
    void finalizeContest_rebuildsScoresAndSynchronizesNormalSubmissions() {
        Long contestId = 42L;

        given(resultRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(result1, result2));
        given(contestSubmissionService.findAllByIdsInOrder(List.of(1L, 2L)))
                .willReturn(List.of(submission1, submission2));
        given(submissionRepository.saveAll(anyList())).willAnswer(invocation -> {
            List<Submission> submissions = invocation.getArgument(0);
            long baseId = 1000L;
            for (Submission submission : submissions) {
                ReflectionTestUtils.setField(submission, "id", baseId++);
            }
            return submissions;
        });

        doAnswer(invocation -> {
            ContestSubmissionBatchExecutor.BatchLoader loader = invocation.getArgument(2);
            Consumer<List<Long>> consumer = invocation.getArgument(3);
            List<Long> ids = loader.load(contestId, null, PageRequest.of(0, invocation.getArgument(1)));
            consumer.accept(ids);
            return null;
        }).when(batchExecutor).processBatches(eq(contestId), anyInt(), any(), any());

        given(resultRepository.findSubmissionIdsByContestId(eq(contestId), isNull(), any()))
                .willReturn(List.of(1L, 2L));

        contestFinalizationService.finalizeContest(contestId);

        verify(rejudgeService).rejudgeAcceptedSubmissions(contestId);
        verify(resultRepository).copyProvisionalToFinal(contestId);
        verify(finalScoreService).deleteScores(contestId, ContestFinalScoreStatus.PROVISIONAL);
        verify(finalScoreService).rebuildScores(contestId, ContestFinalScoreStatus.FINAL);
        verify(contestSubmissionService).purgeContest(contestId);
        verify(scoreboardService).reset(contestId);
        verify(outboxRepository).deleteByContestId(contestId);

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<SubmissionResult> resultCaptor = ArgumentCaptor.forClass(SubmissionResult.class);
        verify(normalSubmissionResultService, times(2)).handleSubmissionResult(idCaptor.capture(), resultCaptor.capture(), any(LocalDateTime.class));
        assertThat(resultCaptor.getAllValues()).hasSize(2)
                .containsExactlyInAnyOrder(SubmissionResult.ACCEPTED, SubmissionResult.WRONG_ANSWER);
    }
}








