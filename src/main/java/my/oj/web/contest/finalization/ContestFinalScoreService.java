package my.oj.web.contest.finalization;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.submission.SubmissionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContestFinalScoreService {

    private final ContestSubmissionResultRepository resultRepository;
    private final ContestFinalScoreRepository finalScoreRepository;
    @Qualifier("contestFinalScoreboardService")
    private final ContestScoreboardService scoreboardService;

    @Transactional
    public void rebuildScores(Long contestId, ContestFinalScoreStatus status, List<ContestSubmissionResult> preloadedResults) {
        finalScoreRepository.deleteByContestIdAndStatus(contestId, status);
        List<ContestScoreboardEntry> entries = calculateEntries(contestId, status == ContestFinalScoreStatus.FINAL, preloadedResults);
        List<ContestFinalScore> scores = new ArrayList<>(entries.size());
        int rank = 1;
        for (ContestScoreboardEntry entry : entries) {
            scores.add(ContestFinalScore.of(
                    contestId,
                    entry.userId(),
                    entry.solvedCount(),
                    entry.penalty(),
                    rank++,
                    status
            ));
        }
        finalScoreRepository.saveAll(scores);
    }

    @Transactional
    public void deleteScores(Long contestId, ContestFinalScoreStatus status) {
        finalScoreRepository.deleteByContestIdAndStatus(contestId, status);
    }

    @Transactional(readOnly = true)
    public List<ContestFinalScore> getScores(Long contestId, ContestFinalScoreStatus status) {
        return finalScoreRepository.findByContestIdAndStatusOrderByRankAsc(contestId, status);
    }

    private List<ContestScoreboardEntry> calculateEntries(Long contestId,
                                                          boolean useFinalResult,
                                                          List<ContestSubmissionResult> preloadedResults) {
        if (!useFinalResult) {
            List<ContestScoreboardEntry> liveEntries = scoreboardService.currentRanking(contestId);
            if (!liveEntries.isEmpty()) {
                return liveEntries;
            }
        }

        List<ContestSubmissionResult> results = preloadedResults != null ? preloadedResults :
                resultRepository.findAllByContestIdWithSubmission(contestId);
        if (results.isEmpty()) {
            return List.of();
        }

        scoreboardService.reset(contestId);
        try {
            for (ContestSubmissionResult result : results) {
                ContestSubmission submission = result.getSubmission();
                SubmissionResult effective = resolveResult(result, useFinalResult);
                if (effective == null) {
                    continue;
                }
                scoreboardService.recordJudgement(
                        result.getId(),
                        contestId,
                        submission.getProblem().getId(),
                        submission.getUser().getId(),
                        submission.getContest().getStartTime(),
                        submission.getSubmittedTime(),
                        effective
                );
            }
            return new ArrayList<>(scoreboardService.currentRanking(contestId));
        } finally {
            scoreboardService.reset(contestId);
        }
    }

    private SubmissionResult resolveResult(ContestSubmissionResult result, boolean useFinalResult) {
        if (useFinalResult && result.getFinalResult() != null) {
            return result.getFinalResult();
        }
        return result.getProvisionalResult();
    }
}








