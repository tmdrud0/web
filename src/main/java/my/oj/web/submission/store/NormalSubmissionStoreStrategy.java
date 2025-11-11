package my.oj.web.submission.store;

import lombok.RequiredArgsConstructor;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.SubmissionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NormalSubmissionStoreStrategy implements SubmissionStoreStrategy {

    private final SubmissionRepository repository;
    private final SubmissionHashDeduplicator deduplicator;

    @Override
    public SubmissionStoreResult save(Submission submission) {
        SubmissionHashDeduplicator.Result<Submission> result =
                deduplicator.save(new NormalCommand(repository, submission));

        Submission persisted = result.entity();
        return new SubmissionStoreResult(persisted.getId(), SubmissionOrigin.NORMAL, result.duplicate());
    }

    private static final class NormalCommand implements SubmissionHashDeduplicator.Command<Submission> {
        private final SubmissionRepository repository;
        private final Submission submission;
        private final String code;

        private NormalCommand(SubmissionRepository repository, Submission submission) {
            this.repository = repository;
            this.submission = submission;
            this.code = submission.getCode();
        }

        @Override
        public void resetCandidate(int attempt) {
            if (attempt > 0) {
                submission.regenerateCodeHash(attempt);
            }
        }

        @Override
        public Optional<Submission> findDuplicate() {
            return repository.findFirstByUserIdAndProblemIdAndCodeHash(
                    submission.getUser().getId(),
                    submission.getProblem().getId(),
                    submission.getCodeHash()
            );
        }

        @Override
        public boolean isSameCode(Submission duplicate) {
            return code.equals(duplicate.getCode());
        }

        @Override
        public Submission save() {
            return repository.save(submission);
        }

        @Override
        public RuntimeException collisionLimitExceeded(int attempt, DataIntegrityViolationException ex) {
            if (ex == null) {
                return new IllegalStateException("Exceeded submission hash collision retries");
            }
            return new IllegalStateException("Exceeded submission hash collision retries", ex);
        }
    }
}
