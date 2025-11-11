package my.oj.web.submission.event.normal;

import lombok.RequiredArgsConstructor;
import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.accepted.AcceptedSubmission;
import my.oj.web.submission.accepted.AcceptedSubmissionRepository;
import my.oj.web.submission.event.guard.UserGuardRepository;
import my.oj.web.user.User;
import my.oj.web.user.activity.DailyActiveUserRepository;
import my.oj.web.user.rank.solved.SolvedBucketUpdater;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NormalSubmissionResultService {
    private final SubmissionRepository submissionRepository;
    private final AcceptedSubmissionRepository acceptedSubmissionRepository;
    private final UserGuardRepository userGuardRepository;
    private final DailyActiveUserRepository dailyActiveUserRepository;
    private final SolvedBucketUpdater solvedBucketUpdater;

    @Transactional
    public void handleSubmissionResult(Long submissionId, SubmissionResult result, LocalDateTime judgedAt) {
        Submission submission = submissionRepository.getReferenceById(submissionId);
        submission.setResult(result);

        if (result == SubmissionResult.ACCEPTED) {
            recordAcceptance(submission, judgedAt);
        }
    }

    private void recordAcceptance(Submission submission, LocalDateTime judgedAt) {
        User user = submission.getUser();
        Problem problem = submission.getProblem();

        AcceptedSubmission accepted = AcceptedSubmission.create(
                user,
                problem,
                submission.getSubmittedTime()
        );

        try {
            acceptedSubmissionRepository.save(accepted);
        } catch (DataIntegrityViolationException e) {
            return;
        }

        userGuardRepository.guard(user.getId());

        LocalDateTime acceptedAt = submission.getSubmittedTime() != null ? submission.getSubmittedTime() : judgedAt;
        user.getStreak().recordSolveAt(acceptedAt);

        long oldSolved = user.getSolvedCount();
        user.incSolvedCount();
        solvedBucketUpdater.incrementFrom(oldSolved);

        dailyActiveUserRepository.upsert(
                submission.getSubmittedTime().toLocalDate(),
                user.getId(),
                submission.getSubmittedTime()
        );
    }
}
