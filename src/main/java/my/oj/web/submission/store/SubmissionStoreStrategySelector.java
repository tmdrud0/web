package my.oj.web.submission.store;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.Contest;
import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Component
@RequiredArgsConstructor
public class SubmissionStoreStrategySelector {
    private final NormalSubmissionStoreStrategy normalStrategy;
    private final ContestSubmissionStoreStrategy contestStrategy;

    public SubmissionStoreResult store(Submission submission) {
        return onContest(submission.getProblem(), submission.getSubmittedTime())
                ? contestStrategy.save(submission)
                : normalStrategy.save(submission);
    }

    public CompletionStage<SubmissionStoreResult> storeAsync(Submission submission) {
        if (onContest(submission.getProblem(), submission.getSubmittedTime())) {
            return contestStrategy.saveAsync(submission);
        }

        try {
            return CompletableFuture.completedFuture(normalStrategy.save(submission));
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    public boolean onContest(Problem problem, LocalDateTime submittedTime) {
        if (problem == null) {
            return false;
        }

        Contest contest = problem.getContest();
        if (contest == null) {
            return false;
        }

        if (submittedTime == null) {
            return false;
        }

        LocalDateTime start = contest.getStartTime();
        LocalDateTime end = contest.getEndTime();

        boolean afterStart = start == null || !submittedTime.isBefore(start);
        boolean beforeEnd = end == null || !submittedTime.isAfter(end);

        return afterStart && beforeEnd;
    }
}
