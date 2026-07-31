package my.oj.web.contest.scoreboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Every attempt seen for one (user, problem), plus what that problem was last credited with in
 * the user's totals. Keeping the attempts and re-deriving the contribution from them is what
 * makes applying judgements order-independent, so both stores hold this same state — the Redis
 * one in a hash, the in-memory one on the heap.
 */
public final class ProblemAttempts {

    private final Map<Long, Long> wrongMinutesBySubmissionId = new HashMap<>();
    private Long acceptedMinutes;
    private Long acceptedSubmissionId;
    private long contributedSolved;
    private long contributedPenalty;

    /**
     * Records an ACCEPTED attempt, keeping whichever one is earliest by
     * {@link ContestScoreboardPolicy#isEarlierAttempt}.
     *
     * @return whether this attempt became the earliest one, so a caller that persists the state
     *         knows there is something to write
     */
    public boolean recordAccepted(long contestMinutes, long contestSubmissionId) {
        if (acceptedMinutes != null && !ContestScoreboardPolicy.isEarlierAttempt(
                contestMinutes,
                contestSubmissionId,
                acceptedMinutes,
                acceptedSubmissionId
        )) {
            return false;
        }
        acceptedMinutes = contestMinutes;
        acceptedSubmissionId = contestSubmissionId;
        return true;
    }

    /** Records a wrong attempt. Keying by submission ID absorbs a repeated delivery. */
    public void recordWrong(long contestSubmissionId, long contestMinutes) {
        wrongMinutesBySubmissionId.put(contestSubmissionId, contestMinutes);
    }

    /** Restores the contribution this problem was last credited with. */
    public void restoreContribution(long solved, long penalty) {
        contributedSolved = solved;
        contributedPenalty = penalty;
    }

    /**
     * Recomputes the contribution from every attempt and returns how it moved against the
     * previously recorded one, which is the only part a caller has to apply to the user totals.
     */
    public ContributionChange applyContribution() {
        ContestScoreboardPolicy.ProblemContribution contribution =
                ContestScoreboardPolicy.computeProblemContribution(
                        acceptedMinutes,
                        acceptedSubmissionId,
                        wrongMinutesBySubmissionId
                );
        ContributionChange change = new ContributionChange(
                contribution.solved() - contributedSolved,
                contribution.penalty() - contributedPenalty,
                contribution.solved(),
                contribution.penalty()
        );
        restoreContribution(contribution.solved(), contribution.penalty());
        return change;
    }

    /** How a problem's contribution moved, and what it is now. */
    public record ContributionChange(long solvedDelta, long penaltyDelta, long solved, long penalty) {

        public boolean isEmpty() {
            return solvedDelta == 0L && penaltyDelta == 0L;
        }
    }
}
