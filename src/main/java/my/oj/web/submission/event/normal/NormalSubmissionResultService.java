package my.oj.web.submission.event.normal;

import lombok.RequiredArgsConstructor;
import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.accepted.AcceptedSubmission;
import my.oj.web.submission.accepted.AcceptedSubmissionRepository;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.activity.DailyActiveUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NormalSubmissionResultService {
    private final SubmissionRepository submissionRepository;
    private final AcceptedSubmissionRepository acceptedSubmissionRepository;
    private final UserRepository userRepository;
    private final DailyActiveUserRepository dailyActiveUserRepository;

    @Transactional
    public void handleSubmissionResult(Long submissionId, SubmissionResult result, LocalDateTime judgedAt) {
        submissionRepository.updateResult(submissionId, result);

        Submission submission = submissionRepository.getReferenceById(submissionId);
        submission.setResult(result);

        if (result == SubmissionResult.ACCEPTED) {
            recordAcceptance(submission);
        }
    }

    private void recordAcceptance(Submission submission) {
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

        User updatedUser = userRepository.getReferenceById(user.getId());
        updatedUser.incSolvedCount();
        updatedUser.getStreak().updateUserStreak();
        submission.setUser(updatedUser);

        dailyActiveUserRepository.upsert(
                submission.getSubmittedTime().toLocalDate(),
                user.getId(),
                submission.getSubmittedTime()
        );
    }
}
