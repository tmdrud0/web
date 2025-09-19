package my.oj.web.contest.finalization;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.finalization.ContestRejudgeService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.event.normal.NormalSubmissionResultService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContestFinalizationService {

    private static final int SYNC_BATCH_SIZE = 500;

    private final ContestFinalScoreService finalScoreService;
    private final ContestScoreboardService scoreboardService;
    private final ContestScoreboardOutboxRepository outboxRepository;
    private final ContestRejudgeService rejudgeService;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestSubmissionService contestSubmissionService;
    private final SubmissionRepository submissionRepository;
    private final NormalSubmissionResultService normalSubmissionResultService;
    private final ContestSubmissionBatchExecutor batchExecutor;

    public ContestFinalizationService(ContestFinalScoreService finalScoreService,
                                      ContestScoreboardService scoreboardService,
                                      ContestScoreboardOutboxRepository outboxRepository,
                                      ContestRejudgeService rejudgeService,
                                      ContestSubmissionResultRepository resultRepository,
                                      ContestSubmissionService contestSubmissionService,
                                      SubmissionRepository submissionRepository,
                                      NormalSubmissionResultService normalSubmissionResultService,
                                      ContestSubmissionBatchExecutor batchExecutor) {
        this.finalScoreService = finalScoreService;
        this.scoreboardService = scoreboardService;
        this.outboxRepository = outboxRepository;
        this.rejudgeService = rejudgeService;
        this.resultRepository = resultRepository;
        this.contestSubmissionService = contestSubmissionService;
        this.submissionRepository = submissionRepository;
        this.normalSubmissionResultService = normalSubmissionResultService;
        this.batchExecutor = batchExecutor;
    }

    public void finalizeContest(Long contestId) {
        rejudgeService.rejudgeAcceptedSubmissions(contestId);
        resultRepository.copyProvisionalToFinal(contestId);

        finalScoreService.deleteScores(contestId, ContestFinalScoreStatus.PROVISIONAL);
        finalScoreService.rebuildScores(contestId, ContestFinalScoreStatus.FINAL);

        syncContestSubmissionsToNormal(contestId);
        contestSubmissionService.purgeContest(contestId);

        scoreboardService.reset(contestId);
        outboxRepository.deleteByContestId(contestId);
    }

    private void syncContestSubmissionsToNormal(Long contestId) {
        batchExecutor.processBatches(
                contestId,
                SYNC_BATCH_SIZE,
                resultRepository::findSubmissionIdsByContestId,
                this::transferBatch
        );
    }

    private void transferBatch(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }
        List<ContestSubmission> submissions = contestSubmissionService.findAllByIdsInOrder(submissionIds);
        if (submissions.isEmpty()) {
            return;
        }

        Map<Long, ContestSubmissionResult> resultMap = loadResults(submissionIds);
        List<Submission> toPersist = new ArrayList<>(submissions.size());
        List<SubmissionSyncPayload> payloads = new ArrayList<>(submissions.size());

        for (ContestSubmission submission : submissions) {
            ContestSubmissionResult result = resultMap.get(submission.getId());
            SubmissionResult effectiveResult = resolveResult(result);
            LocalDateTime judgedAt = resolveJudgedAt(result, submission);

            Submission normal = Submission.create(
                    submission.getUser(),
                    submission.getProblem(),
                    submission.getCode(),
                    submission.getSubmittedTime()
            );
            normal.setResult(effectiveResult);

            toPersist.add(normal);
            payloads.add(new SubmissionSyncPayload(effectiveResult, judgedAt));
        }

        List<Submission> saved = submissionRepository.saveAll(toPersist);
        for (int i = 0; i < saved.size(); i++) {
            Submission savedSubmission = saved.get(i);
            SubmissionSyncPayload payload = payloads.get(i);
            normalSubmissionResultService.handleSubmissionResult(
                    savedSubmission.getId(),
                    payload.result(),
                    payload.judgedAt()
            );
        }
    }

    private Map<Long, ContestSubmissionResult> loadResults(List<Long> submissionIds) {
        List<ContestSubmissionResult> results = resultRepository.findAllById(submissionIds);
        Map<Long, ContestSubmissionResult> map = new HashMap<>(results.size());
        for (ContestSubmissionResult result : results) {
            map.put(result.getId(), result);
        }
        return map;
    }

    private SubmissionResult resolveResult(ContestSubmissionResult result) {
        if (result == null) {
            return SubmissionResult.PENDING;
        }
        if (result.getFinalResult() != null) {
            return result.getFinalResult();
        }
        return result.getProvisionalResult();
    }

    private LocalDateTime resolveJudgedAt(ContestSubmissionResult result, ContestSubmission submission) {
        if (result == null) {
            return submission.getSubmittedTime();
        }
        if (result.getFinalJudgedAt() != null) {
            return result.getFinalJudgedAt();
        }
        if (result.getProvisionalJudgedAt() != null) {
            return result.getProvisionalJudgedAt();
        }
        return submission.getSubmittedTime();
    }

    private record SubmissionSyncPayload(SubmissionResult result, LocalDateTime judgedAt) {
    }
}

