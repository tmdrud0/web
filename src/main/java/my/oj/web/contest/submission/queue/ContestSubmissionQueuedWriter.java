package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmissionService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface ContestSubmissionQueuedWriter {

    ContestSubmissionService.ContestSubmissionCreateResult save(ContestSubmissionQueueRequest request);

    default CompletionStage<ContestSubmissionService.ContestSubmissionCreateResult> saveAsync(
            ContestSubmissionQueueRequest request
    ) {
        try {
            return CompletableFuture.completedFuture(save(request));
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
}
