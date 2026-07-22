package my.oj.web.contest.submission.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface ContestSubmissionWriter {

    ContestSubmissionService.ContestSubmissionCreateResult save(ContestSubmissionWriteRequest request);

    default CompletionStage<ContestSubmissionService.ContestSubmissionCreateResult> saveAsync(
            ContestSubmissionWriteRequest request
    ) {
        try {
            return CompletableFuture.completedFuture(save(request));
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
}
