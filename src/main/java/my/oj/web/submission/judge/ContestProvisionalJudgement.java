package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Backs off when either substitute judge is enabled, so a context never holds two implementations of
 * the same interface.
 *
 * <p>The two substitutes are independent settings rather than values of one, and
 * {@code @ConditionalOnProperty} is not repeatable in this Boot version, so the pair is written as
 * one conjunction. Writing it as an expression is also what keeps the default honest:
 * {@code matchIfMissing} is unavailable here, and spelling the properties' defaults out in the
 * expression is what makes "nothing configured" select this stub rather than none.</p>
 */
@Component
@ConditionalOnExpression("'${contest.submission.judge.latency.enabled:false}' == 'false' && "
        + "'${contest.submission.judge.deterministic.enabled:false}' == 'false'")
public class ContestProvisionalJudgement implements ContestSubmissionJudgement {

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        // TODO: integrate real contest judging logic
        return SubmissionResult.PARTIAL_ACCEPTED;
    }
}
