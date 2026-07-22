package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.submission.SubmissionResult;

public interface ContestSubmissionJudgement {

    SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission);
}
