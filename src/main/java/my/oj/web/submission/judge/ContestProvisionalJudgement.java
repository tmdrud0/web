package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Backs off when {@link LatencyProfileContestJudgement} is enabled, so a context never holds two
 * implementations of the same interface.
 */
@Component
@ConditionalOnProperty(prefix = "contest.submission.judge.latency", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class ContestProvisionalJudgement implements ContestSubmissionJudgement {

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        // TODO: integrate real contest judging logic
        return SubmissionResult.PARTIAL_ACCEPTED;
    }
}
