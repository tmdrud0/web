package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContestScoreboardRebuildService {

    private static final int REBUILD_BATCH_SIZE = 1_000;

    private final ContestScoreboardService scoreboardService;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestSubmissionService contestSubmissionService;
    private final ContestSubmissionBatchExecutor batchExecutor;

    public void rebuildFromContestResults(Long contestId) {
        scoreboardService.reset(contestId);

        batchExecutor.processBatches(
                contestId,
                REBUILD_BATCH_SIZE,
                resultRepository::findSubmissionIdsByContestId,
                submissionIds -> replayBatch(contestId, submissionIds)
        );
    }

    private void replayBatch(Long contestId, List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }

        List<ContestSubmission> submissions = new ArrayList<>(contestSubmissionService.findAllByIdsInOrder(submissionIds));
        if (submissions.isEmpty()) {
            return;
        }

        Map<Long, ContestSubmissionResult> resultMap = resultRepository.findAllById(submissionIds)
                .stream()
                .collect(Collectors.toMap(ContestSubmissionResult::getId, Function.identity()));

        submissions.sort(Comparator
                .comparing(ContestSubmission::getSubmittedTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ContestSubmission::getId));

        for (ContestSubmission submission : submissions) {
            ContestSubmissionResult result = resultMap.get(submission.getId());
            SubmissionResult effectiveResult = resolveResult(result);
            LocalDateTime contestStart = submission.getContest() != null
                    ? submission.getContest().getStartTime()
                    : null;
            scoreboardService.recordJudgement(
                    submission.getId(),
                    contestId,
                    submission.getProblem().getId(),
                    submission.getUser().getId(),
                    contestStart,
                    submission.getSubmittedTime(),
                    effectiveResult
            );
        }
    }

    private SubmissionResult resolveResult(ContestSubmissionResult result) {
        if (result == null) {
            return SubmissionResult.PENDING;
        }
        if (result.getFinalResult() != null) {
            return result.getFinalResult();
        }
        if (result.getProvisionalResult() != null) {
            return result.getProvisionalResult();
        }
        return SubmissionResult.PENDING;
    }
}