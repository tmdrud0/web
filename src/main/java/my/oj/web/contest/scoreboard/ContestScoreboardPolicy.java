package my.oj.web.contest.scoreboard;

import java.time.Duration;
import java.time.LocalDateTime;

public final class ContestScoreboardPolicy {

    public static final int PENALTY_PER_WRONG_MINUTES = 5;
    public static final long SCORE_SOLVED_WEIGHT = 1_000_000_000L;
    public static final long SCORE_PENALTY_WEIGHT = 1_000L;

    private ContestScoreboardPolicy() {
    }

    public static long computePenalty(LocalDateTime contestStart,
                                      LocalDateTime submittedTime,
                                      long wrongAttempts) {
        return wrongAttempts * PENALTY_PER_WRONG_MINUTES
                + computeContestMinutes(contestStart, submittedTime);
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
}
