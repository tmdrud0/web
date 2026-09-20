package my.oj.web.contest.scoreboard.recovery;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
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
 *
 * <p>What this class decides is which rows to offer and in what sized chunks. Applying them, and
 * recording that they were applied, is {@link ContestScoreboardReplayApplication} - shared with
 * {@code redis-seq} so that neither mode can drift on the transaction boundary, which is the part
 * both of them are easy to get wrong about and neither of them is about.</p>
 */
@Service
@RequiredArgsConstructor
public class ContestScoreboardFullReplayService {

    private final ContestSubmissionResultRepository resultRepository;
    private final ContestSubmissionBatchExecutor batchExecutor;
    private final ContestScoreboardReplayApplication replayApplication;
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
            List<ContestScoreboardReplayRow> chunk =
                    rows.subList(start, Math.min(start + replayBatchSize, rows.size()));
            replayApplication.apply(
                    chunk.stream().map(ContestScoreboardFullReplayService::request).toList(),
                    "contest " + contestId
            );
            applied += chunk.size();
        }
        return applied;
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
