package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.stream.JdbcContestScoreboardAppliedAtWriter;
import my.oj.web.contest.submission.core.ContestScoreboardReplayRow;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.SubmissionResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The {@code full-replay} recovery mode: re-send every stored judgement to the scoreboard.
 *
 * <p>This is the non-destructive counterpoint to {@code ContestScoreboardRebuildService}. It never
 * resets a contest, so whatever the RDB snapshot restored stays in place and only the rows the
 * snapshot never saw are new. Re-sending a row the scoreboard already applied is absorbed by the
 * script's processed set, so replaying more than necessary is safe.</p>
 *
 * <p>The mode deliberately leaves the stream checkpoint alone: a rebuild request carries no offset,
 * so the script writes neither the offset nor its dependent state.</p>
 */
@Service
@RequiredArgsConstructor
public class ContestScoreboardFullReplayService {

    private final ContestScoreboardApplier scoreboardApplier;
    private final ContestSubmissionResultRepository resultRepository;
    private final ContestSubmissionBatchExecutor batchExecutor;
    private final JdbcContestScoreboardAppliedAtWriter appliedAtWriter;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardRecoveryProperties properties;

    /**
     * Re-sends every contest's stored judgements.
     *
     * @return how many results were offered to the scoreboard, the already-applied ones included -
     *         the script, not this service, decides which of them change anything
     */
    public int replayAllContests() {
        int replayed = 0;
        for (Long contestId : resultRepository.findDistinctContestIds()) {
            replayed += replayContest(contestId);
        }
        return replayed;
    }

    /** Re-sends one contest's stored judgements. */
    public int replayContest(Long contestId) {
        int[] replayed = {0};
        batchExecutor.processBatchesOf(
                contestId,
                properties.fullReplay().dbBatchSize(),
                (scopeId, afterId, pageable) -> resultRepository.findReplayRowsByContestId(
                        scopeId, afterId, SubmissionResult.PENDING, pageable),
                ContestScoreboardReplayRow::getSubmissionId,
                rows -> replayed[0] += replayBatch(contestId, rows)
        );
        return replayed[0];
    }

    private int replayBatch(Long contestId, List<ContestScoreboardReplayRow> rows) {
        int replayBatchSize = properties.fullReplay().replayBatchSize();
        int applied = 0;
        for (int start = 0; start < rows.size(); start += replayBatchSize) {
            applied += applyChunk(
                    contestId,
                    rows.subList(start, Math.min(start + replayBatchSize, rows.size()))
            );
        }
        return applied;
    }

    /**
     * The lock is taken per chunk and released between them rather than held for the whole replay,
     * so a long replay delays the live stream path by at most one chunk and never blocks it while
     * this service is reading MySQL.
     */
    private int applyChunk(Long contestId, List<ContestScoreboardReplayRow> rows) {
        List<ContestScoreboardApplier.ApplyRequest> requests = rows.stream()
                .map(ContestScoreboardFullReplayService::request)
                .toList();
        applyLock.withLock(() -> {
            List<ContestScoreboardApplier.ApplyResult> results = scoreboardApplier.applyAll(requests);
            String failure = results.stream()
                    .filter(result -> !result.succeeded())
                    .map(ContestScoreboardApplier.ApplyResult::errorMessage)
                    .findFirst()
                    .orElse(null);
            if (failure != null || results.size() != requests.size()) {
                throw new IllegalStateException("Failed to replay contest " + contestId
                        + " onto the scoreboard: "
                        + (failure == null ? "batch stopped before every result was applied" : failure));
            }
            appliedAtWriter.markApplied(requests.stream()
                    .map(request -> request.update().contestSubmissionId())
                    .toList());
        });
        return requests.size();
    }

    /**
     * A rebuild request carries no stream offset, which is what keeps a replay from moving the
     * checkpoint the RDB snapshot restored.
     */
    private static ContestScoreboardApplier.ApplyRequest request(ContestScoreboardReplayRow row) {
        return ContestScoreboardApplier.ApplyRequest.rebuild(
                row.getSubmissionId(),
                new ContestScoreboardUpdate(
                        row.getSubmissionId(),
                        row.getContestId(),
                        row.getProblemId(),
                        row.getUserId(),
                        row.getContestStart(),
                        row.getSubmittedTime(),
                        row.getResult(),
                        null
                )
        );
    }
}
