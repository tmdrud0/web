package my.oj.web.submission.judge;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Component;

@Slf4j
@Component("contestJudgement")
public class ContestProvisionalJudgement implements Judgement, ContestSubmissionJudgement {

    @Override
    public Submission judgeSubmission(Submission submission) {
        // TODO: integrate real contest judging logic
        submission.setResult(SubmissionResult.PARTIAL_ACCEPTED);
        return submission;
    }

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        // TODO: integrate real contest judging logic
        return SubmissionResult.PARTIAL_ACCEPTED;
    }
}
