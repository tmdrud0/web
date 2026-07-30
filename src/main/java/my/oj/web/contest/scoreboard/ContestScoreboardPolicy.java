package my.oj.web.contest.scoreboard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

public final class ContestScoreboardPolicy {

    public static final int PENALTY_PER_WRONG_MINUTES = 5;
    public static final long SCORE_SOLVED_WEIGHT = 1_000_000_000L;
    public static final long SCORE_PENALTY_WEIGHT = 1_000L;

    private ContestScoreboardPolicy() {
    }

    public static double computeScore(long solved,
                                      long penalty,
                                      long userIdNumeric) {
        return solved * SCORE_SOLVED_WEIGHT
                - penalty * SCORE_PENALTY_WEIGHT
                - userIdNumeric;
    }

    public static long computeContestMinutes(LocalDateTime contestStart,
                                             LocalDateTime submittedTime) {
        if (contestStart == null || submittedTime == null) {
            return 0L;
        }
        long seconds = Duration.between(contestStart, submittedTime).toSeconds();
        if (seconds <= 0L) {
            return 0L;
        }
        return (seconds + 59L) / 60L;
    }

    /**
     * Orders two attempts on the same problem by {@code (contestMinutes, submissionId)}.
     * The submission ID breaks ties within one minute so that the winner never depends on
     * which judgement was applied first. Snowflake IDs grow with time, so it orders attempts
     * the same way the submission clock does.
     */
    public static boolean isEarlierAttempt(long contestMinutes,
                                           long submissionId,
                                           long otherContestMinutes,
                                           long otherSubmissionId) {
        if (contestMinutes != otherContestMinutes) {
            return contestMinutes < otherContestMinutes;
        }
        return submissionId < otherSubmissionId;
    }

    /**
     * Recomputes what one problem contributes to a user's summary from the full set of
     * attempts seen so far. Recomputing instead of accumulating is what makes applying
     * judgements order-independent: the same attempt set always yields the same
     * contribution, no matter in which order — or how many times — each attempt arrived.
     *
     * @param acceptedMinutes    contest minute of the earliest ACCEPTED attempt, or {@code null} if unsolved
     * @param acceptedSubmissionId submission ID of that ACCEPTED attempt, unused when unsolved
     * @param wrongMinutesBySubmissionId contest minute of every wrong attempt, keyed by submission ID
     */
    public static ProblemContribution computeProblemContribution(Long acceptedMinutes,
                                                                 Long acceptedSubmissionId,
                                                                 Map<Long, Long> wrongMinutesBySubmissionId) {
        if (acceptedMinutes == null || acceptedSubmissionId == null) {
            return ProblemContribution.NONE;
        }
        long wrongBefore = countWrongAttemptsBefore(
                acceptedMinutes,
                acceptedSubmissionId,
                wrongMinutesBySubmissionId
        );
        return new ProblemContribution(1, acceptedMinutes + wrongBefore * PENALTY_PER_WRONG_MINUTES);
    }

    private static long countWrongAttemptsBefore(long acceptedMinutes,
                                                 long acceptedSubmissionId,
                                                 Map<Long, Long> wrongMinutesBySubmissionId) {
        if (wrongMinutesBySubmissionId == null || wrongMinutesBySubmissionId.isEmpty()) {
            return 0L;
        }
        long counted = 0L;
        for (Map.Entry<Long, Long> wrongAttempt : wrongMinutesBySubmissionId.entrySet()) {
            if (isEarlierAttempt(
                    wrongAttempt.getValue(),
                    wrongAttempt.getKey(),
                    acceptedMinutes,
                    acceptedSubmissionId
            )) {
                counted++;
            }
        }
        return counted;
    }

    /** What one problem currently adds to a user's {@code solved} and {@code penalty} totals. */
    public record ProblemContribution(long solved, long penalty) {

        public static final ProblemContribution NONE = new ProblemContribution(0L, 0L);
    }
}
