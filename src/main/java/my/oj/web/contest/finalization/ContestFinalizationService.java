package my.oj.web.contest.finalization;

import my.oj.web.contest.Contest;
import my.oj.web.contest.ContestRepository;
import my.oj.web.contest.scoreboard.ContestScoreboardMaintenanceService;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionProblemCache;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.accepted.AcceptedSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContestFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(ContestFinalizationService.class);

    private final ContestFinalScoreService finalScoreService;
    private final ContestRepository contestRepository;
    private final ContestScoreboardMaintenanceService scoreboardMaintenanceService;
    private final ContestRejudgeService rejudgeService;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestSubmissionService contestSubmissionService;
    private final ContestSubmissionProblemCache problemCache;
    private final ContestFinalizationBatchRepository batchRepository;
    private final TransactionTemplate writeTxTemplate;

    public ContestFinalizationService(ContestFinalScoreService finalScoreService,
                                      ContestRepository contestRepository,
                                      ContestScoreboardMaintenanceService scoreboardMaintenanceService,
                                      ContestRejudgeService rejudgeService,
                                      ContestSubmissionResultRepository resultRepository,
                                      ContestSubmissionService contestSubmissionService,
                                      ContestSubmissionProblemCache problemCache,
                                      ContestFinalizationBatchRepository batchRepository,
                                      PlatformTransactionManager transactionManager) {
        this.finalScoreService = finalScoreService;
        this.contestRepository = contestRepository;
        this.scoreboardMaintenanceService = scoreboardMaintenanceService;
        this.rejudgeService = rejudgeService;
        this.resultRepository = resultRepository;
        this.contestSubmissionService = contestSubmissionService;
        this.problemCache = problemCache;
        this.batchRepository = batchRepository;
        this.writeTxTemplate = new TransactionTemplate(transactionManager);
    }

    public void finalizeContest(Long contestId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("Contest not found: " + contestId));

        if (contest.isFinalized()) {
            log.info("Contest {} already finalized at {}", contestId, contest.getFinalizedAt());
            return;
        }

        rejudgeService.rejudgeAcceptedSubmissions(contestId);
        writeTxTemplate.executeWithoutResult(status -> resultRepository.copyProvisionalToFinal(contestId));

        List<ContestSubmissionResult> results = resultRepository.findAllByContestIdWithSubmission(contestId);

        finalScoreService.deleteScores(contestId, ContestFinalScoreStatus.PROVISIONAL);
        finalScoreService.rebuildScores(contestId, ContestFinalScoreStatus.FINAL, results);

        applyContestResultsToNormal(contest, results);

        contestSubmissionService.purgeContest(contestId);
        scoreboardMaintenanceService.clearLiveContestState(contestId);

        contest.markFinalized(LocalDateTime.now());
        contestRepository.save(contest);
        problemCache.evictContest(contestId);
    }

    void applyContestResultsToNormal(Contest contest, List<ContestSubmissionResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<ContestFinalizationBatchRepository.SubmissionRow> submissionRows = new ArrayList<>(results.size());
        Map<Long, Map<Long, ContestSubmission>> acceptedByUser = new HashMap<>();
        Map<Long, Long> initialSolved = new HashMap<>();

        for (ContestSubmissionResult result : results) {
            ContestSubmission submission = result.getSubmission();
            if (submission == null) {
                continue;
            }
            SubmissionResult effective = resolveResult(result, true);
            submissionRows.add(new ContestFinalizationBatchRepository.SubmissionRow(
                    submission.getUser().getId(),
                    submission.getProblem().getId(),
                    submission.getSubmittedTime(),
                    submission.getCode(),
                    submission.getCodeHash(),
                    effective
            ));

            initialSolved.putIfAbsent(submission.getUser().getId(), submission.getUser().getSolvedCount());

            if (effective != SubmissionResult.ACCEPTED) {
                continue;
            }

            acceptedByUser
                    .computeIfAbsent(submission.getUser().getId(), id -> new HashMap<>())
                    .merge(submission.getProblem().getId(), submission, (existing, candidate) ->
                            existing.getSubmittedTime().isBefore(candidate.getSubmittedTime()) ? existing : candidate);
        }

        batchRepository.insertSubmissions(submissionRows);

        if (acceptedByUser.isEmpty()) {
            return;
        }

        LocalDateTime contestEnd = contest.getEndTime();
        List<AcceptedSubmission> acceptedRows = new ArrayList<>();
        Map<Long, ContestFinalizationBatchRepository.UserSolvedDelta> solvedChanges = new HashMap<>();

        for (Map.Entry<Long, Map<Long, ContestSubmission>> entry : acceptedByUser.entrySet()) {
            Long userId = entry.getKey();
            Map<Long, ContestSubmission> perProblem = entry.getValue();
            if (perProblem.isEmpty()) {
                continue;
            }
            long delta = perProblem.size();
            long oldSolved = initialSolved.getOrDefault(userId, 0L);
            solvedChanges.merge(
                    userId,
                    new ContestFinalizationBatchRepository.UserSolvedDelta(oldSolved, delta),
                    (existing, incoming) -> new ContestFinalizationBatchRepository.UserSolvedDelta(
                            existing.oldSolved(),
                            existing.delta() + incoming.delta()
                    )
            );

            for (ContestSubmission submission : perProblem.values()) {
                acceptedRows.add(AcceptedSubmission.create(
                        submission.getUser(),
                        submission.getProblem(),
                        submission.getSubmittedTime()
                ));
            }
        }

        if (acceptedRows.isEmpty()) {
            return;
        }

        persistContestResults(contestEnd, acceptedRows, solvedChanges);
    }

    void persistContestResults(LocalDateTime contestEnd,
                               List<AcceptedSubmission> acceptedRows,
                               Map<Long, ContestFinalizationBatchRepository.UserSolvedDelta> solvedChanges) {
        batchRepository.insertAcceptedSubmissions(acceptedRows);
        LocalDateTime effectiveTime = contestEnd != null ? contestEnd : LocalDateTime.now();
        batchRepository.incrementSolvedCountsAndDailyActivity(solvedChanges, effectiveTime);
    }

    private SubmissionResult resolveResult(ContestSubmissionResult result, boolean useFinalResult) {
        if (result == null) {
            return SubmissionResult.PENDING;
        }
        if (useFinalResult && result.getFinalResult() != null) {
            return result.getFinalResult();
        }
        return result.getProvisionalResult();
    }
}
