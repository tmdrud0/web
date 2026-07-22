package my.oj.web.contest.submission.judge;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "contest.submission.judge.result-writer",
        name = "mode",
        havingValue = "immediate",
        matchIfMissing = true
)
class ImmediateContestSubmissionJudgeResultWriter implements ContestSubmissionJudgeResultWriter {

    private final ContestSubmissionService contestSubmissionService;

    @Override
    public void persist(ContestSubmissionJudgeProjection submission,
                        SubmissionResult result,
                        LocalDateTime judgedAt) {
        contestSubmissionService.applyProvisionalResult(submission, result, judgedAt);
    }
}
