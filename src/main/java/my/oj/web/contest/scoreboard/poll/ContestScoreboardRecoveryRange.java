package my.oj.web.contest.scoreboard.poll;

/**
 * A persisted {@code (fromExclusive, throughInclusive]} range of Redis sequences a rollback took away.
 *
 * @param generation the row's key; completion is compared against it, so finishing one generation can
 *                   never mark a newer one done
 */
public record ContestScoreboardRecoveryRange(long generation, long fromExclusive, long throughInclusive) {
}
