package my.oj.web.contest.scoreboard.rebuild;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.stream.JdbcContestScoreboardAppliedAtWriter;
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

    private final ContestScoreboardApplier scoreboardApplier;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestSubmissionService contestSubmissionService;
    private final ContestSubmissionBatchExecutor batchExecutor;
    private final JdbcContestScoreboardAppliedAtWriter appliedAtWriter;

    public int rebuildAllFromContestResults() {
        List<Long> contestIds = resultRepository.findDistinctContestIds();
        contestIds.forEach(this::rebuildFromContestResults);
        return contestIds.size();
    }

    public void rebuildFromContestResults(Long contestId) {
        scoreboardApplier.reset(contestId);

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

        List<ContestScoreboardApplier.ApplyRequest> requests = new ArrayList<>(submissions.size());
        for (ContestSubmission submission : submissions) {
            SubmissionResult effectiveResult = resolveResult(resultMap.get(submission.getId()));
            // An unjudged submission has nothing to contribute, and applying it would mark the
            // submission as handled - the real judgement would then be skipped for good.
            if (effectiveResult == null) {
                continue;
            }
            LocalDateTime contestStart = submission.getContest() != null
                    ? submission.getContest().getStartTime()
                    : null;
            requests.add(ContestScoreboardApplier.ApplyRequest.rebuild(
                    submission.getId(),
                    new ContestScoreboardUpdate(
                            submission.getId(),
                            contestId,
                            submission.getProblem().getId(),
                            submission.getUser().getId(),
                            contestStart,
                            submission.getSubmittedTime(),
                            effectiveResult,
                            null
                    )
            ));
        }
        if (requests.isEmpty()) {
            return;
        }

        List<ContestScoreboardApplier.ApplyResult> results = scoreboardApplier.applyAll(requests);
        String failure = results.stream()
                .filter(result -> !result.succeeded())
                .map(ContestScoreboardApplier.ApplyResult::errorMessage)
                .findFirst()
                .orElse(null);
        if (failure != null) {
            throw new IllegalStateException(
                    "Failed to replay contest " + contestId + " onto the scoreboard: " + failure
            );
        }
        appliedAtWriter.markApplied(requests.stream()
                .map(request -> request.update().contestSubmissionId())
                .toList());
    }

    /**
     * @return the result to replay, or {@code null} when the submission has not been judged
     */
    private SubmissionResult resolveResult(ContestSubmissionResult result) {
        if (result == null) {
            return null;
        }
        if (result.getFinalResult() != null) {
            return result.getFinalResult();
        }
        return result.getProvisionalResult();
    }
}
