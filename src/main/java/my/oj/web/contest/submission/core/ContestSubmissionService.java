package my.oj.web.contest.submission.core;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutbox;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxCreatedEvent;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.CodeHashGenerator;
import my.oj.web.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContestSubmissionService {

    private static final int MAX_HASH_COLLISION_RETRY = 5;

    private final ContestSubmissionRepository repository;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestScoreboardOutboxService scoreboardOutboxService;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public ContestSubmissionCreateResult create(User user, Problem problem, String code, LocalDateTime submittedTime) {
        int attempt = 0;
        while (true) {
            String codeHash = CodeHashGenerator.generateWithAttempt(code, attempt);
            ContestSubmission contestSubmission = ContestSubmission.create(user, problem, code, codeHash, submittedTime);
            try {
                ContestSubmission saved = repository.save(contestSubmission);
                return new ContestSubmissionCreateResult(saved, false);
            } catch (DataIntegrityViolationException ex) {
                var existing = repository.findByContestIdAndProblemIdAndUserIdAndCodeHash(
                        contestSubmission.getContest().getId(),
                        contestSubmission.getProblem().getId(),
                        contestSubmission.getUser().getId(),
                        codeHash
                );
                if (existing.isPresent()) {
                    if (existing.get().getCode().equals(code)) {
                        return new ContestSubmissionCreateResult(existing.get(), true);
                    }
                    attempt++;
                    if (attempt > MAX_HASH_COLLISION_RETRY) {
                        throw new IllegalStateException("Exceeded contest submission hash collision retries", ex);
                    }
                    continue;
                }
                throw new IllegalArgumentException("Contest submission could not be saved due to unexpected data conflict", ex);
            }
        }
    }

    @Transactional
    public void applyProvisionalResult(Long contestSubmissionId, SubmissionResult result, LocalDateTime judgedAt) {
        ContestSubmission contestSubmission = repository.findById(contestSubmissionId)
                .orElseThrow(() -> new IllegalStateException("Contest submission not found: " + contestSubmissionId));
        ContestSubmissionResult submissionResult = resultRepository.findById(contestSubmissionId)
                .orElseGet(() -> resultRepository.save(ContestSubmissionResult.pending(contestSubmission)));
        submissionResult.recordProvisional(result, judgedAt);

        ContestScoreboardOutbox outbox = scoreboardOutboxService.enqueue(
                contestSubmission.getId(),
                contestSubmission.getContest().getId(),
                contestSubmission.getProblem().getId(),
                contestSubmission.getUser().getId(),
                contestSubmission.getContest().getStartTime(),
                contestSubmission.getSubmittedTime(),
                result,
                judgedAt
        );
        publisher.publishEvent(new ContestScoreboardOutboxCreatedEvent(outbox.getId()));
    }

    @Transactional(readOnly = true)
    public ContestSubmission getById(Long contestSubmissionId) {
        return repository.findById(contestSubmissionId)
                .orElseThrow(() -> new IllegalStateException("Contest submission not found: " + contestSubmissionId));
    }

    @Transactional(readOnly = true)
    public List<ContestSubmission> findAllByIdsInOrder(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findAllByIdInOrderById(ids);
    }

    @Transactional
    public void purgeContest(Long contestId) {
        resultRepository.deleteByContestId(contestId);
        repository.deleteByContestId(contestId);
    }

    public record ContestSubmissionCreateResult(ContestSubmission submission, boolean duplicate) { }
}
