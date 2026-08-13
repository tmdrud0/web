package my.oj.web.contest.scoreboard.memory;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

/** In-process mirror of the Redis offset and commutative scoreboard contract. */
public class InMemoryContestScoreboardApplier implements ContestScoreboardApplier {

    private final InMemoryContestScoreboard scoreboard;
    private long currentStreamOffset = -1L;

    public InMemoryContestScoreboardApplier(InMemoryContestScoreboard scoreboard) {
        this.scoreboard = scoreboard;
    }

    @Override
    public synchronized Long apply(ApplyRequest request) {
        validate(request);
        Long streamOffset = request.streamOffset();
        if (streamOffset != null) {
            if (streamOffset <= currentStreamOffset) {
                return currentStreamOffset;
            }
            if (!request.allowOffsetGap() && streamOffset != currentStreamOffset + 1L) {
                throw new IllegalStateException(
                        "Non-contiguous scoreboard stream offset: expected "
                                + (currentStreamOffset + 1L) + " but received " + streamOffset
                );
            }
        }

        scoreboard.apply(request.update());
        if (streamOffset != null) {
            currentStreamOffset = streamOffset;
        }
        return currentStreamOffset;
    }

    @Override
    public synchronized long currentStreamOffset() {
        return currentStreamOffset;
    }

    @Override
    public void reset(long contestId) {
        scoreboard.reset(contestId);
    }

    private static void validate(ApplyRequest request) {
        ContestScoreboardUpdate update = request == null ? null : request.update();
        if (request == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            throw new IllegalArgumentException("Scoreboard event and update fields are required");
        }
    }
}
