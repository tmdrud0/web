package my.oj.web.submission;

import lombok.RequiredArgsConstructor;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionStoreStrategySelector storeSelector;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public SubmissionReceipt submit(SubmitSubmissionCommand cmd) {
        User user = userRepository.getReferenceById(cmd.userId());
        Problem problem = problemRepository.getReferenceById(cmd.problemId());
        LocalDateTime submittedTime = LocalDateTime.now();

        Submission submission = Submission.create(user, problem, cmd.code(), submittedTime);
        SubmissionStoreResult result = storeSelector.store(submission);

        if (!result.duplicate()) {
            publisher.publishEvent(new SubmissionSubmittedEvent(
                    result.submissionId(),
                    result.origin()
            ));
        }

        return new SubmissionReceipt(result.submissionId(), result.origin(), result.duplicate());
    }
}

