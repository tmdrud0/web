package my.oj.web.contest.submission.core;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.ContestSubmissionIdGenerator;
import my.oj.web.problem.Problem;
import my.oj.web.submission.CodeHashGenerator;
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
    private final ContestSubmissionDuplicateRegistry duplicateRegistry;
    private final ContestSubmissionIdGenerator idGenerator;
    private final ContestSubmissionWriter submissionWriter;

    public ContestSubmissionCreateResult create(User user, Problem problem, String code, LocalDateTime submittedTime) {
        Contest contest = problem.getContest();
        if (contest != null && contest.isFinalized()) {
            throw new IllegalStateException("Contest submissions are closed for contest " + contest.getId());
        }

        String canonicalHash = CodeHashGenerator.generate(code);
        ContestSubmissionWriteRequest request = new ContestSubmissionWriteRequest(
                contest.getId(),
                problem.getId(),
                user.getId(),
                code,
                canonicalHash,
                submittedTime
        ).withReservedSubmissionId(idGenerator.nextId());
        return submissionWriter.save(request);
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
        ContestSubmissionWriteRequest request = new ContestSubmissionWriteRequest(
                contest.getId(),
                problem.getId(),
                user.getId(),
                code,
                canonicalHash,
                submittedTime
        ).withReservedSubmissionId(idGenerator.nextId());

        return submissionWriter.saveAsync(request);
    }

    public ContestSubmissionJudgeProjection getJudgeProjectionById(Long contestSubmissionId) {
        return repository.findJudgeProjectionById(contestSubmissionId)
                .orElseThrow(() -> new IllegalStateException("Contest submission not found: " + contestSubmissionId));
    }

    public Optional<ContestSubmissionStoredJudgeResultProjection> findStoredJudgeResultById(
            Long contestSubmissionId
    ) {
        return resultRepository.findStoredJudgeResultBySubmissionId(contestSubmissionId);
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
