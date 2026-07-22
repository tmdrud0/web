package my.oj.web.contest.submission.core;

import java.time.LocalDateTime;

public record ContestSubmissionWriteRequest(
        long contestId,
        long problemId,
        long userId,
        String code,
        String codeHash,
        LocalDateTime submittedTime,
        Long reservedSubmissionId
) {

    public ContestSubmissionWriteRequest(long contestId,
                                         long problemId,
                                         long userId,
                                         String code,
                                         String codeHash,
                                         LocalDateTime submittedTime) {
        this(contestId, problemId, userId, code, codeHash, submittedTime, null);
    }

    public ContestSubmissionWriteRequest withReservedSubmissionId(long submissionId) {
        return new ContestSubmissionWriteRequest(
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
