package my.oj.web.contest.submission.support;

import java.util.Optional;

public interface ContestSubmissionDuplicateRegistry {

    Optional<Long> findDuplicateSubmissionId(long contestId, long problemId, long userId, String codeHash);

    void registerSubmission(long contestId, long problemId, long userId, String codeHash, long submissionId);

    void purgeContest(long contestId);
}

