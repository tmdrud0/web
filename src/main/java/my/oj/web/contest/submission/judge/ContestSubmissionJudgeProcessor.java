package my.oj.web.contest.submission.judge;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.SubmissionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ContestSubmissionJudgeProcessor {

    private final ContestSubmissionService contestSubmissionService;
    @Qualifier("contestJudgement")
    private final ContestSubmissionJudgement contestJudgement;
    private final ContestSubmissionJudgeResultWriter resultWriter;

    public void judge(Long contestSubmissionId) {
        if (contestSubmissionId == null) {
            return;
        }

        ContestSubmissionJudgeProjection submission =
                contestSubmissionService.getJudgeProjectionById(contestSubmissionId);
        SubmissionResult result = contestJudgement.judgeSubmission(submission);
        resultWriter.persist(
                submission,
                result,
                LocalDateTime.now()
        );
    }
}
