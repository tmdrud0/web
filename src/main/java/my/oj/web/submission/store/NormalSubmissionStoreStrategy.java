package my.oj.web.submission.store;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.SubmissionRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NormalSubmissionStoreStrategy implements SubmissionStoreStrategy {
    private final SubmissionRepository repository;

    @Override
    public SubmissionStoreResult save(Submission submission) {
        Long userId = submission.getUser().getId();
        Long problemId = submission.getProblem().getId();
        String code = submission.getCode();
        String codeHash = submission.getCodeHash();

        Submission duplicate = repository.findFirstByUserIdAndProblemIdAndCodeHash(userId, problemId, codeHash)
                .orElse(null);
        if (duplicate != null && code.equals(duplicate.getCode())) {
            return new SubmissionStoreResult(duplicate.getId(), SubmissionOrigin.NORMAL, true);
        }

        Submission persisted = repository.save(submission);
        return new SubmissionStoreResult(persisted.getId(), SubmissionOrigin.NORMAL, false);
    }
}

