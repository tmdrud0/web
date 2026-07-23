package my.oj.web.contest.submission.core;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.Contest;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxCreatedNotifier;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.ContestSubmissionIdGenerator;
import my.oj.web.problem.Problem;
import my.oj.web.submission.CodeHashGenerator;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Service
@RequiredArgsConstructor
public class ContestSubmissionService {

    private final ContestSubmissionRepository repository;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestScoreboardOutboxService scoreboardOutboxService;
    private final ContestScoreboardOutboxCreatedNotifier scoreboardOutboxNotifier;
    private final ContestSubmissionDuplicateRegistry duplicateRegistry;
    private final ContestSubmissionIdGenerator idGenerator;
    private final ContestSubmissionWriter submissionWriter;

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

        ContestSubmissionWriteRequest request = new ContestSubmissionWriteRequest(
                contest.getId(),
                problem.getId(),
                user.getId(),
                code,
                canonicalHash,
                submittedTime
        ).withReservedSubmissionId(idGenerator.nextId());
        ContestSubmissionCreateResult result = submissionWriter.save(request);
        duplicateRegistry.registerSubmission(contest.getId(), problem.getId(), user.getId(), canonicalHash, result.submission().getId());
        return result;
    }

    public CompletionStage<ContestSubmissionCreateResult> createAsync(
            User user,
            Problem problem,
            String code,
            LocalDateTime submittedTime
    ) {
        Contest contest = problem.getContest();
        if (contest != null && contest.isFinalized()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Contest submissions are closed for contest " + contest.getId())
            );
        }

        String canonicalHash = CodeHashGenerator.generate(code);
        Optional<ContestSubmission> cachedDuplicate =
                duplicateRegistry.findDuplicateSubmissionId(contest.getId(), problem.getId(), user.getId(), canonicalHash)
                        .flatMap(repository::findById)
                        .filter(existing -> code.equals(existing.getCode()));
        if (cachedDuplicate.isPresent()) {
            ContestSubmission duplicate = cachedDuplicate.get();
            duplicateRegistry.registerSubmission(
                    contest.getId(), problem.getId(), user.getId(), canonicalHash, duplicate.getId()
            );
            return CompletableFuture.completedFuture(new ContestSubmissionCreateResult(duplicate, true));
        }

        ContestSubmissionWriteRequest request = new ContestSubmissionWriteRequest(
                contest.getId(),
                problem.getId(),
                user.getId(),
                code,
                canonicalHash,
                submittedTime
        ).withReservedSubmissionId(idGenerator.nextId());

        return submissionWriter.saveAsync(request).thenApply(result -> {
            duplicateRegistry.registerSubmission(
                    contest.getId(), problem.getId(), user.getId(), canonicalHash, result.submission().getId()
            );
            return result;
        });
    }

    @Transactional
    public void applyProvisionalResult(ContestSubmissionJudgeProjection submission,
                                       SubmissionResult result,
                                       LocalDateTime judgedAt) {
        int insertedResult = resultRepository.insertProvisionalIfAbsent(
                submission.getSubmissionId(),
                submission.getContestId(),
                result.name(),
                judgedAt
        );
        if (insertedResult == 0) {
            return;
        }

        boolean insertedOutbox = scoreboardOutboxService.insertPendingIfAbsent(
                submission.getSubmissionId(),
                submission.getContestId(),
                submission.getProblemId(),
                submission.getUserId(),
                submission.getContestStart(),
                submission.getSubmittedTime(),
                result,
                judgedAt
        );
        if (insertedOutbox) {
            scoreboardOutboxNotifier.notifyCreated(submission.getSubmissionId());
        }
    }

    public ContestSubmissionJudgeProjection getJudgeProjectionById(Long contestSubmissionId) {
        return repository.findJudgeProjectionById(contestSubmissionId)
                .orElseThrow(() -> new IllegalStateException("Contest submission not found: " + contestSubmissionId));
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
