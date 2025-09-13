package my.oj.web.submission.store;

import my.oj.web.submission.Submission;

public interface SubmissionStoreStrategy {
    SubmissionStoreResult save(Submission submission);
}
