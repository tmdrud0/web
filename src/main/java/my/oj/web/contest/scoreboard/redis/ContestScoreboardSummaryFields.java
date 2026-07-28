package my.oj.web.contest.scoreboard.redis;

/**
 * Field names of the per-user summary hash. The reader parses them here; the writer sets them
 * from {@link ContestScoreboardRedisScript}, so the two must stay in step.
 */
final class ContestScoreboardSummaryFields {

    static final String SOLVED = "solved";
    static final String PENALTY = "penalty";

    private ContestScoreboardSummaryFields() {
    }
}
