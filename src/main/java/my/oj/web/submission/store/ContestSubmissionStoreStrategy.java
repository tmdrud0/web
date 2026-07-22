package my.oj.web.submission.store;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionOrigin;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionStage;

@Component
@RequiredArgsConstructor
public class ContestSubmissionStoreStrategy implements SubmissionStoreStrategy {

    private final ContestSubmissionService contestSubmissionService;

    @Override
    public SubmissionStoreResult save(Submission submission) {
        var contest = submission.getProblem().getContest();
        if (contest == null) {
            throw new IllegalStateException("Contest submission requires contest problem");
        }

        ContestSubmissionService.ContestSubmissionCreateResult result = contestSubmissionService.create(
                submission.getUser(),
                submission.getProblem(),
                submission.getCode(),
                submission.getSubmittedTime()
        );
        ContestSubmission contestSubmission = result.submission();
        return new SubmissionStoreResult(contestSubmission.getId(), SubmissionOrigin.CONTEST, result.duplicate());
    }

    public CompletionStage<SubmissionStoreResult> saveAsync(Submission submission) {
        var contest = submission.getProblem().getContest();
        if (contest == null) {
            throw new IllegalStateException("Contest submission requires contest problem");
        }

        return contestSubmissionService.createAsync(
                submission.getUser(),
                submission.getProblem(),
                submission.getCode(),
                submission.getSubmittedTime()
        ).thenApply(result -> new SubmissionStoreResult(
                result.submission().getId(),
                SubmissionOrigin.CONTEST,
                result.duplicate()
        ));
    }
}


