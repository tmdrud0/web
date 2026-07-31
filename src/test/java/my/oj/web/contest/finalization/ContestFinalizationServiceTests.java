package my.oj.web.contest.finalization;

import my.oj.web.contest.Contest;
import my.oj.web.contest.ContestRepository;
import my.oj.web.contest.scoreboard.ContestScoreboardMaintenanceService;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionProblemCache;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.accepted.AcceptedSubmission;
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
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestFinalizationServiceTests {

    @Mock
    private ContestFinalScoreService finalScoreService;
    @Mock
    private ContestRepository contestRepository;
    @Mock
    private ContestScoreboardMaintenanceService scoreboardMaintenanceService;
    @Mock
    private ContestRejudgeService rejudgeService;
    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestSubmissionService contestSubmissionService;
    @Mock
    private ContestSubmissionProblemCache problemCache;
    @Mock
    private ContestFinalizationBatchRepository batchRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private ContestFinalizationService contestFinalizationService;

    private Contest contest;
    private ContestSubmission acceptedSubmission;
    private ContestSubmissionResult acceptedResult;
    private ContestSubmissionResult wrongResult;

    @BeforeEach
    void setUp() {
        User user = User.withState(1L, "user", "pass", 0L, new Streak());
        contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 42L);
        ReflectionTestUtils.setField(contest, "startTime", LocalDateTime.of(2025, 9, 19, 9, 0));
        ReflectionTestUtils.setField(contest, "endTime", LocalDateTime.of(2025, 9, 19, 13, 0));

        Problem problemA = Problem.create("A", contest, 1L);
        ReflectionTestUtils.setField(problemA, "id", 10L);
        Problem problemB = Problem.create("B", contest, 2L);
        ReflectionTestUtils.setField(problemB, "id", 11L);

        acceptedSubmission = ContestSubmission.create(
                user,
                problemA,
                "codeA",
                "hashA",
                LocalDateTime.of(2025, 9, 19, 9, 30)
        );
        ReflectionTestUtils.setField(acceptedSubmission, "id", 100L);

        ContestSubmission wrongSubmission = ContestSubmission.create(
                user,
                problemB,
                "codeB",
                "hashB",
                LocalDateTime.of(2025, 9, 19, 9, 40)
        );
        ReflectionTestUtils.setField(wrongSubmission, "id", 101L);

        acceptedResult = ContestSubmissionResult.pending(acceptedSubmission);
        ReflectionTestUtils.setField(acceptedResult, "id", acceptedSubmission.getId());
        acceptedResult.recordProvisional(SubmissionResult.ACCEPTED, LocalDateTime.of(2025, 9, 19, 9, 35));
        acceptedResult.recordFinal(SubmissionResult.ACCEPTED, LocalDateTime.of(2025, 9, 19, 9, 36));

        wrongResult = ContestSubmissionResult.pending(wrongSubmission);
        ReflectionTestUtils.setField(wrongResult, "id", wrongSubmission.getId());
        wrongResult.recordProvisional(SubmissionResult.WRONG_ANSWER, LocalDateTime.of(2025, 9, 19, 9, 45));
        wrongResult.recordFinal(SubmissionResult.WRONG_ANSWER, LocalDateTime.of(2025, 9, 19, 9, 46));
    }

    @Test
    void finalizeContest_processesSubmissionsInBatches() {
        Long contestId = contest.getId();
        List<ContestSubmissionResult> results = List.of(acceptedResult, wrongResult);

        given(contestRepository.findById(contestId)).willReturn(Optional.of(contest));
        given(resultRepository.findAllByContestIdWithSubmission(contestId)).willReturn(results);
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());

        contestFinalizationService.finalizeContest(contestId);

        verify(rejudgeService).rejudgeAcceptedSubmissions(contestId);
        verify(resultRepository).copyProvisionalToFinal(contestId);
        verify(finalScoreService).deleteScores(contestId, ContestFinalScoreStatus.PROVISIONAL);
        verify(finalScoreService).rebuildScores(contestId, ContestFinalScoreStatus.FINAL, results);
        verify(contestRepository).save(contest);
        verify(contestSubmissionService).purgeContest(contestId);
        verify(problemCache).evictContest(contestId);
        verify(scoreboardMaintenanceService).clearLiveContestState(contestId);

        ArgumentCaptor<List<ContestFinalizationBatchRepository.SubmissionRow>> submissionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(batchRepository).insertSubmissions(submissionsCaptor.capture());
        assertThat(submissionsCaptor.getValue()).hasSize(2);

        ArgumentCaptor<List<AcceptedSubmission>> acceptedCaptor = ArgumentCaptor.forClass(List.class);
        verify(batchRepository).insertAcceptedSubmissions(acceptedCaptor.capture());
        assertThat(acceptedCaptor.getValue()).hasSize(1);

        ArgumentCaptor<Map<Long, ContestFinalizationBatchRepository.UserSolvedDelta>> solvedCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(batchRepository).incrementSolvedCountsAndDailyActivity(solvedCaptor.capture(), timeCaptor.capture());
        assertThat(solvedCaptor.getValue()).containsEntry(1L, new ContestFinalizationBatchRepository.UserSolvedDelta(0L, 1L));
        assertThat(timeCaptor.getValue()).isEqualTo(contest.getEndTime());
    }

    @Test
    void finalizeContest_returnsEarlyWhenAlreadyFinalized() {
        contest.markFinalized(LocalDateTime.now().minusMinutes(5));
        given(contestRepository.findById(contest.getId())).willReturn(Optional.of(contest));

        contestFinalizationService.finalizeContest(contest.getId());

        verify(rejudgeService, never()).rejudgeAcceptedSubmissions(anyLong());
        verify(resultRepository, never()).copyProvisionalToFinal(anyLong());
        verify(batchRepository, never()).insertSubmissions(anyList());
        verify(batchRepository, never()).insertAcceptedSubmissions(anyList());
        verify(batchRepository, never()).incrementSolvedCountsAndDailyActivity(anyMap(), any());
    }
}
