package my.oj.web.contest.scoreboard;

import java.time.Duration;

public final class ContestScoreboardConstants {

    private ContestScoreboardConstants() {
    }

    public static final int PENALTY_PER_WRONG_MINUTES = 5;
    public static final long SCORE_SOLVED_WEIGHT = 1_000_000_000L;
    public static final long SCORE_PENALTY_WEIGHT = 1_000L;

    public static final Duration LOCK_TTL = Duration.ofSeconds(5);
    public static final long INITIAL_BACKOFF_MILLIS = 10L;
    public static final long MAX_BACKOFF_MILLIS = 200L;
    public static final int MAX_LOCK_ATTEMPTS = 6;
}
