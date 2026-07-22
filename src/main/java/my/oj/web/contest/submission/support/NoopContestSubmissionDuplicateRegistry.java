package my.oj.web.contest.submission.support;

import java.util.Optional;

public class NoopContestSubmissionDuplicateRegistry implements ContestSubmissionDuplicateRegistry {

    @Override
    public Optional<Long> findDuplicateSubmissionId(long contestId, long problemId, long userId, String codeHash) {
        return Optional.empty();
    }

    @Override
    public void registerSubmission(long contestId, long problemId, long userId, String codeHash, long submissionId) {
        // no-op
    }

    @Override
    public void purgeContest(long contestId) {
        // no-op
    }
}
