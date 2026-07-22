package my.oj.web.contest.submission.queue;

import java.time.LocalDateTime;

public record ContestSubmissionQueueRequest(
        long contestId,
        long problemId,
        long userId,
        String code,
        String codeHash,
        LocalDateTime submittedTime,
        Long reservedSubmissionId
) {

    public ContestSubmissionQueueRequest(long contestId,
                                         long problemId,
                                         long userId,
                                         String code,
                                         String codeHash,
                                         LocalDateTime submittedTime) {
        this(contestId, problemId, userId, code, codeHash, submittedTime, null);
    }

    public ContestSubmissionQueueRequest withReservedSubmissionId(long submissionId) {
        return new ContestSubmissionQueueRequest(
                contestId,
                problemId,
                userId,
                code,
                codeHash,
                submittedTime,
                submissionId
        );
    }
}
