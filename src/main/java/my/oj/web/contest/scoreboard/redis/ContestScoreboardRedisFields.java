package my.oj.web.contest.scoreboard.redis;

/**
 * Field names of the scoreboard hashes. {@link ContestScoreboardRedisScript} writes the same
 * schema from Lua, so the two must be changed together.
 *
 * <p>The problem hash keeps one field per attempt instead of running counters, so that a
 * judgement can be applied in any order and any number of times:
 *
 * <pre>
 * a:min          contest minute of the earliest ACCEPTED attempt seen so far
 * a:sid          submission ID of that attempt, used to break ties inside one minute
 * w:&lt;sid&gt;        contest minute of one wrong attempt
 * c:solved       what this problem currently contributes to summary.solved
 * c:penalty      what this problem currently contributes to summary.penalty
 * </pre>
 */
final class ContestScoreboardRedisFields {

    static final String SUMMARY_INITIALIZED = "initialized";
    static final String SUMMARY_SOLVED = "solved";
    static final String SUMMARY_PENALTY = "penalty";
    static final String INITIALIZED_FLAG = "1";

    static final String ACCEPTED_MINUTES = "a:min";
    static final String ACCEPTED_SUBMISSION_ID = "a:sid";
    static final String WRONG_PREFIX = "w:";
    static final String CONTRIBUTED_SOLVED = "c:solved";
    static final String CONTRIBUTED_PENALTY = "c:penalty";

    private ContestScoreboardRedisFields() {
    }

    static String wrongAttempt(long contestSubmissionId) {
        return WRONG_PREFIX + contestSubmissionId;
    }
}
