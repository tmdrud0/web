package my.oj.web.contest.submission.queue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.messaging.ContestJudgeOutboxWriter;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.problem.Problem;
import my.oj.web.user.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContestSubmissionBulkProcessor {

    private final ContestJudgeOutboxWriter judgeOutboxWriter;
    private final ContestSubmissionBatchPersistence batchPersistence;

    @PersistenceContext
    private EntityManager entityManager;

    public ContestSubmissionBulkProcessor(ContestJudgeOutboxWriter judgeOutboxWriter,
                                          ContestSubmissionBatchPersistence batchPersistence) {
        this.judgeOutboxWriter = judgeOutboxWriter;
        this.batchPersistence = batchPersistence;
    }

    @Transactional
    public List<ContestSubmissionService.ContestSubmissionCreateResult> process(List<ContestSubmissionWriteRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        List<ContestSubmissionService.ContestSubmissionCreateResult> responses = new ArrayList<>(requests.size());
        List<ContestSubmission> toPersist = new ArrayList<>();
        Map<DedupKey, ContestSubmission> pendingByKey = new LinkedHashMap<>();
        List<PendingResult> pendingResults = new ArrayList<>(requests.size());

        for (ContestSubmissionWriteRequest request : requests) {
            DedupKey key = DedupKey.from(request);
            ContestSubmission queuedDuplicate = pendingByKey.get(key);
            if (queuedDuplicate != null) {
                pendingResults.add(new PendingResult(queuedDuplicate, true));
                continue;
            }

            ContestSubmission submission = createSubmission(request);
            toPersist.add(submission);
            pendingByKey.put(key, submission);
            pendingResults.add(new PendingResult(submission, false));
        }

        if (!toPersist.isEmpty()) {
            ContestSubmissionBatchInsertResult insertResult = batchPersistence.insertAll(toPersist);
            Map<Long, ContestSubmissionService.ContestSubmissionCreateResult> resultsByReservedId =
                    new LinkedHashMap<>();
            List<Long> insertedSubmissionIds = new ArrayList<>();
            for (ContestSubmission submission : toPersist) {
                ContestSubmissionBatchInsertResult.Resolution resolution =
                        insertResult.resolutionFor(submission.getId());
                ContestSubmission resolvedSubmission = resolution.duplicate()
                        ? ContestSubmission.placeholder(resolution.submissionId())
                        : submission;
                resultsByReservedId.put(
                        submission.getId(),
                        new ContestSubmissionService.ContestSubmissionCreateResult(
                                resolvedSubmission,
                                resolution.duplicate()
                        )
                );
                if (!resolution.duplicate()) {
                    insertedSubmissionIds.add(resolution.submissionId());
                }
            }
            for (PendingResult pendingResult : pendingResults) {
                ContestSubmissionService.ContestSubmissionCreateResult persisted =
                        resultsByReservedId.get(pendingResult.submission().getId());
                if (persisted == null) {
                    throw new IllegalStateException(
                            "Missing persisted contest submission result for reserved id "
                                    + pendingResult.submission().getId()
                    );
                }
                responses.add(new ContestSubmissionService.ContestSubmissionCreateResult(
                        persisted.submission(),
                        pendingResult.queuedDuplicate() || persisted.duplicate()
                ));
            }
            judgeOutboxWriter.enqueueAll(insertedSubmissionIds);
            entityManager.clear();
        }

        return responses;
    }

    private ContestSubmission createSubmission(ContestSubmissionWriteRequest request) {
        Contest contest = entityManager.getReference(Contest.class, request.contestId());
        User user = entityManager.getReference(User.class, request.userId());
        Problem problem = entityManager.getReference(Problem.class, request.problemId());
        ContestSubmission submission = ContestSubmission.create(
                contest,
                user,
                problem,
                request.code(),
                request.codeHash(),
                request.submittedTime()
        );
        Long reservedSubmissionId = request.reservedSubmissionId();
        if (reservedSubmissionId == null) {
            throw new IllegalArgumentException("Contest submission request must include reservedSubmissionId");
        }
        submission.assignId(reservedSubmissionId);
        return submission;
    }

    private record DedupKey(long contestId, long problemId, long userId, String codeHash) {
        private static DedupKey from(ContestSubmissionWriteRequest request) {
            return new DedupKey(
                    request.contestId(),
                    request.problemId(),
                    request.userId(),
                    request.codeHash()
            );
        }
    }

    private record PendingResult(ContestSubmission submission, boolean queuedDuplicate) {
    }
}
