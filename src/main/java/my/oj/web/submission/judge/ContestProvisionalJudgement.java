package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Component;

@Component
public class ContestProvisionalJudgement implements ContestSubmissionJudgement {

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        // TODO: integrate real contest judging logic
        return SubmissionResult.PARTIAL_ACCEPTED;
    }
}
