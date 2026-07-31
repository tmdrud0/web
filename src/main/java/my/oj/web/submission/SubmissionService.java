package my.oj.web.submission;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.support.ContestSubmissionRateLimitExceededException;
import my.oj.web.contest.submission.support.ContestSubmissionRateLimiter;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.dto.SubmissionReceipt;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import my.oj.web.submission.event.SubmissionSubmittedEvent;
import my.oj.web.submission.store.SubmissionStoreResult;
import my.oj.web.submission.store.SubmissionStoreStrategySelector;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletionStage;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionStoreStrategySelector storeSelector;
    private final ApplicationEventPublisher publisher;
    private final ContestSubmissionRateLimiter contestSubmissionRateLimiter;

    public SubmissionReceipt submit(SubmitSubmissionCommand cmd) {
        User user = userRepository.getReferenceById(cmd.userId());
        Problem problem = problemRepository.findWithContestById(cmd.problemId())
                .orElseThrow(() -> new IllegalStateException("Problem not found: " + cmd.problemId()));
        LocalDateTime submittedTime = LocalDateTime.now();
        boolean contestSubmission = storeSelector.onContest(problem, submittedTime);
        Contest contest = contestSubmission ? problem.getContest() : null;
        Long contestId = contest != null ? contest.getId() : null;

        Submission submission = Submission.create(user, problem, cmd.code(), submittedTime);
        if (contestId != null) {
            contestSubmissionRateLimiter.tryAcquire(contestId, user.getId())
                    .ifPresent(retryAfter -> {
                        throw new ContestSubmissionRateLimitExceededException(retryAfter);
                    });
        }

        SubmissionStoreResult result;
        try {
            result = storeSelector.store(submission);
        } catch (RuntimeException ex) {
            if (contestId != null) {
                contestSubmissionRateLimiter.release(contestId, user.getId());
            }
            throw ex;
        }

        return completeSubmission(result);
    }

    public CompletionStage<SubmissionReceipt> submitAsync(SubmitSubmissionCommand cmd) {
        User user = userRepository.getReferenceById(cmd.userId());
        Problem problem = problemRepository.findWithContestById(cmd.problemId())
                .orElseThrow(() -> new IllegalStateException("Problem not found: " + cmd.problemId()));
        LocalDateTime submittedTime = LocalDateTime.now();
        boolean contestSubmission = storeSelector.onContest(problem, submittedTime);
        Contest contest = contestSubmission ? problem.getContest() : null;
        Long contestId = contest != null ? contest.getId() : null;

        Submission submission = Submission.create(user, problem, cmd.code(), submittedTime);
        if (contestId != null) {
            contestSubmissionRateLimiter.tryAcquire(contestId, user.getId())
                    .ifPresent(retryAfter -> {
                        throw new ContestSubmissionRateLimitExceededException(retryAfter);
                    });
        }

        CompletionStage<SubmissionStoreResult> storeStage;
        try {
            storeStage = storeSelector.storeAsync(submission);
        } catch (RuntimeException ex) {
            if (contestId != null) {
                contestSubmissionRateLimiter.release(contestId, user.getId());
            }
            throw ex;
        }

        return storeStage.whenComplete((result, error) -> {
            if (error != null && contestId != null) {
                contestSubmissionRateLimiter.release(contestId, user.getId());
            }
        }).thenApply(this::completeSubmission);
    }

    private SubmissionReceipt completeSubmission(SubmissionStoreResult result) {
        if (!result.duplicate() && result.origin() == SubmissionOrigin.NORMAL) {
            publisher.publishEvent(new SubmissionSubmittedEvent(
                    result.submissionId(),
                    result.origin()
            ));
        }

        return new SubmissionReceipt(result.submissionId(), result.origin(), result.duplicate());
    }
}

