package my.oj.web.submission.judge;

import my.oj.web.submission.Submission;

public interface Judgement {

    Submission judgeSubmission(Submission submission);
}