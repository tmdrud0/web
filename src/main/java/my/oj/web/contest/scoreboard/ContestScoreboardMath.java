package my.oj.web.contest.scoreboard;

import java.time.Duration;
import java.time.LocalDateTime;

final class ContestScoreboardMath {

    private ContestScoreboardMath() {
    }

    static long computePenalty(LocalDateTime contestStart,
                                LocalDateTime submittedTime,
                                long wrongAttempts) {
        long minutes = computeContestMinutes(contestStart, submittedTime);
        return wrongAttempts * ContestScoreboardConstants.PENALTY_PER_WRONG_MINUTES + minutes;
    }

    static double computeScore(long solved,
                                long penalty,
                                long userIdNumeric) {
        return solved * ContestScoreboardConstants.SCORE_SOLVED_WEIGHT
                - penalty * ContestScoreboardConstants.SCORE_PENALTY_WEIGHT
                - userIdNumeric;
    }

    private static long computeContestMinutes(LocalDateTime contestStart, LocalDateTime submittedTime) {
        if (contestStart == null || submittedTime == null) {
            return 0L;
        }
        long seconds = Duration.between(contestStart, submittedTime).toSeconds();
        if (seconds <= 0) {
            return 0L;
        }
        return (long) Math.ceil(seconds / 60.0);
    }
}