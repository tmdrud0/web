package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmission;

import java.util.List;

public interface ContestSubmissionBatchPersistence {

    void insertAll(List<ContestSubmission> submissions);
}
