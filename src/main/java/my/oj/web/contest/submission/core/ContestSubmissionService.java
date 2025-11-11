package my.oj.web.contest.submission.core;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.Contest;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutbox;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxCreatedEvent;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import my.oj.web.contest.submission.queue.ContestSubmissionQueueRequest;
import my.oj.web.contest.submission.queue.ContestSubmissionQueuedWriter;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistry;
import my.oj.web.problem.Problem;
import my.oj.web.submission.CodeHashGenerator;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContestSubmissionService {

    private final ContestSubmissionRepository repository;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestScoreboardOutboxService scoreboardOutboxService;
    private final ApplicationEventPublisher publisher;
    private final ContestSubmissionDuplicateRegistry duplicateRegistry;
    private final ContestSubmissionQueuedWriter queuedWriter;

    @Transactional(readOnly = true)
    public ContestSubmissionCreateResult create(User user, Problem problem, String code, LocalDateTime submittedTime) {
        Contest contest = problem.getContest();
        if (contest != null && contest.isFinalized()) {
            throw new IllegalStateException("Contest submissions are closed for contest " + contest.getId());
        }

        String canonicalHash = CodeHashGenerator.generate(code);
        Optional<ContestSubmission> cachedDuplicate =
                duplicateRegistry.findDuplicateSubmissionId(contest.getId(), problem.getId(), user.getId(), canonicalHash)
                        .flatMap(repository::findById)
                        .filter(existing -> code.equals(existing.getCode()));
        if (cachedDuplicate.isPresent()) {
            ContestSubmission duplicate = cachedDuplicate.get();
            duplicateRegistry.registerSubmission(contest.getId(), problem.getId(), user.getId(), canonicalHash, duplicate.getId());
            return new ContestSubmissionCreateResult(duplicate, true);
        }

        ContestSubmissionQueueRequest request = new ContestSubmissionQueueRequest(
                contest.getId(),
                problem.getId(),
                user.getId(),
                code,
                canonicalHash,
                submittedTime
        );
        ContestSubmissionCreateResult result = queuedWriter.save(request);
        if (!result.duplicate()) {
            duplicateRegistry.registerSubmission(contest.getId(), problem.getId(), user.getId(), canonicalHash, result.submission().getId());
        }
        return result;
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
        duplicateRegistry.purgeContest(contestId);
    }

    public record ContestSubmissionCreateResult(ContestSubmission submission, boolean duplicate) { }
}
