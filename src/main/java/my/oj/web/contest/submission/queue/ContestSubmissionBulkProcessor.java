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

    private final ContestSubmissionWriteAmplifier writeAmplifier;
    private final ContestJudgeOutboxWriter judgeOutboxWriter;
    private final ContestSubmissionBatchPersistence batchPersistence;

    @PersistenceContext
    private EntityManager entityManager;

    public ContestSubmissionBulkProcessor(ContestSubmissionWriteAmplifier writeAmplifier,
                                          ContestJudgeOutboxWriter judgeOutboxWriter,
                                          ContestSubmissionBatchPersistence batchPersistence) {
        this.writeAmplifier = writeAmplifier;
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

        for (ContestSubmissionWriteRequest request : requests) {
            DedupKey key = DedupKey.from(request);
            ContestSubmission queuedDuplicate = pendingByKey.get(key);
            if (queuedDuplicate != null) {
                responses.add(new ContestSubmissionService.ContestSubmissionCreateResult(queuedDuplicate, true));
                continue;
            }

            ContestSubmission submission = createSubmission(request);
            toPersist.add(submission);
            pendingByKey.put(key, submission);
            responses.add(new ContestSubmissionService.ContestSubmissionCreateResult(submission, false));
        }

        if (!toPersist.isEmpty()) {
            batchPersistence.insertAll(toPersist);
            judgeOutboxWriter.enqueueAll(toPersist.stream().map(ContestSubmission::getId).toList());
            writeAmplifier.amplify(toPersist);
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
}
