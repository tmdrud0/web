package my.oj.web.contest.finalization;

import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.judge.Judgement;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContestRejudgeService {

    private static final int REJUDGE_BATCH_SIZE = 1_000;

    private final ContestSubmissionService contestSubmissionService;
    private final ContestSubmissionResultRepository resultRepository;
    private final Judgement finalJudgement;
    private final ContestSubmissionBatchExecutor batchExecutor;
    private final TransactionTemplate readTxTemplate;
    private final TransactionTemplate writeTxTemplate;

    public ContestRejudgeService(ContestSubmissionService contestSubmissionService,
                                 ContestSubmissionResultRepository resultRepository,
                                 @Qualifier("fullJudge") Judgement finalJudgement,
                                 ContestSubmissionBatchExecutor batchExecutor,
                                 PlatformTransactionManager transactionManager) {
        this.contestSubmissionService = contestSubmissionService;
        this.resultRepository = resultRepository;
        this.finalJudgement = finalJudgement;
        this.batchExecutor = batchExecutor;
        this.readTxTemplate = new TransactionTemplate(transactionManager);
        this.readTxTemplate.setReadOnly(true);
        this.writeTxTemplate = new TransactionTemplate(transactionManager);
    }

    public void rejudgeAcceptedSubmissions(Long contestId) {
        batchExecutor.processBatchesNonTransactional(
                contestId,
                REJUDGE_BATCH_SIZE,
                (cid, afterId, pageable) -> resultRepository.findSubmissionIdsByContestIdAndProvisionalResult(
                        cid,
                        SubmissionResult.ACCEPTED,
                        afterId,
                        pageable
                ),
                this::processBatch
        );
    }

    private void processBatch(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }

        List<ContestSubmission> submissions = readTxTemplate.execute(status ->
                contestSubmissionService.findAllByIdsInOrder(submissionIds)
        );
        if (submissions.isEmpty()) {
            return;
        }

        Map<Long, ContestSubmissionResult> resultMap = readTxTemplate.execute(status -> loadExistingResults(submissionIds));
        List<ContestSubmissionResult> toUpdate = new ArrayList<>(submissions.size());

        for (ContestSubmission submission : submissions) {
            ContestSubmissionResult result = resultMap.computeIfAbsent(
                    submission.getId(),
                    id -> ContestSubmissionResult.pending(submission)
            );

            Submission workingCopy = Submission.create(
                    submission.getUser(),
                    submission.getProblem(),
                    submission.getCode(),
                    submission.getSubmittedTime()
            );
            Submission judged = finalJudgement.judgeSubmission(workingCopy);
            SubmissionResult finalResult = judged.getResult();
            LocalDateTime judgedAt = LocalDateTime.now();

            result.recordProvisional(finalResult, judgedAt);
            result.recordFinal(finalResult, judgedAt);
            toUpdate.add(result);
        }

        writeTxTemplate.executeWithoutResult(status -> resultRepository.saveAll(toUpdate));
    }

    private Map<Long, ContestSubmissionResult> loadExistingResults(List<Long> submissionIds) {
        List<ContestSubmissionResult> results = resultRepository.findAllById(submissionIds);
        Map<Long, ContestSubmissionResult> map = new HashMap<>(results.size());
        for (ContestSubmissionResult result : results) {
            map.put(result.getId(), result);
        }
        return map;
    }
}

