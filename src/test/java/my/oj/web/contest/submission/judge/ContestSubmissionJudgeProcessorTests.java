package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.core.ContestSubmissionStoredJudgeResultProjection;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ContestSubmissionJudgeProcessorTests {

    @Mock
    private ContestSubmissionService submissionService;

    @Mock
    private ContestSubmissionJudgement judgement;

    @Mock
    private ContestSubmissionJudgeProjection projection;

    @Mock
    private ContestSubmissionJudgeResultWriter resultWriter;

    @Test
    void reusesJudgeProjectionWhenPersistingResult() {
        given(submissionService.getJudgeProjectionById(91L)).willReturn(projection);
        given(judgement.judgeSubmission(projection)).willReturn(SubmissionResult.PARTIAL_ACCEPTED);
        ContestSubmissionJudgeProcessor processor =
                new ContestSubmissionJudgeProcessor(submissionService, judgement, resultWriter);

        processor.judge(91L);

        verify(judgement).judgeSubmission(projection);
        verify(resultWriter).persist(
                org.mockito.ArgumentMatchers.same(projection),
                org.mockito.ArgumentMatchers.eq(SubmissionResult.PARTIAL_ACCEPTED),
                any(LocalDateTime.class)
        );
    }

    @Test
    void republishesStoredResultWithoutCallingJudgeAgain() {
        ContestSubmissionStoredJudgeResultProjection stored =
                org.mockito.Mockito.mock(ContestSubmissionStoredJudgeResultProjection.class);
        LocalDateTime judgedAt = LocalDateTime.of(2026, 8, 8, 12, 5);
        given(stored.getSubmissionId()).willReturn(91L);
        given(stored.getContestId()).willReturn(10L);
        given(stored.getProblemId()).willReturn(20L);
        given(stored.getUserId()).willReturn(30L);
        given(stored.getContestStart()).willReturn(judgedAt.minusHours(2));
        given(stored.getSubmittedTime()).willReturn(judgedAt.minusMinutes(1));
        given(stored.getResult()).willReturn(SubmissionResult.PARTIAL_ACCEPTED);
        given(stored.getJudgedAt()).willReturn(judgedAt);
        given(submissionService.findStoredJudgeResultById(91L)).willReturn(Optional.of(stored));
        ContestSubmissionJudgeProcessor processor =
                new ContestSubmissionJudgeProcessor(submissionService, judgement, resultWriter);

        processor.judge(91L);

        verifyNoInteractions(judgement);
        verify(submissionService, never()).getJudgeProjectionById(91L);
        verify(resultWriter).republish(new ContestSubmissionJudgeResultCommand(
                91L,
                10L,
                20L,
                30L,
                judgedAt.minusHours(2),
                judgedAt.minusMinutes(1),
                SubmissionResult.PARTIAL_ACCEPTED,
                judgedAt
        ));
    }
}
