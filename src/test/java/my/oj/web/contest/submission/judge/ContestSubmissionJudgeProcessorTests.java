package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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
}
