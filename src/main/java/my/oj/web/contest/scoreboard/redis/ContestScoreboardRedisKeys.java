package my.oj.web.contest.scoreboard.redis;

final class ContestScoreboardRedisKeys {

    static final String STREAM_OFFSET = "contest:scoreboard:stream:offset";
    static final String STREAM_DB_PENDING = "contest:scoreboard:stream:db-pending";

    /**
     * Allocator for the recovery sequence. Global rather than per contest: the mapping below is a
     * single global hash, so a global allocator is the only scope at which "has this sequence been
     * handed out twice" can be answered exactly.
     */
    static final String SEQUENCE = "contest:scoreboard:seq";

    /** submissionId to the sequence the scoreboard last applied it under. */
    static final String SUBMISSION_SEQUENCE = "contest:scoreboard:submission-seq";

    private static final String PREFIX = "contest:scoreboard:";

    private ContestScoreboardRedisKeys() {
    }

    static String ranking(long contestId) {
        return PREFIX + contestId + ":ranking";
    }

    static String summary(long contestId, long userId) {
        return userPrefix(contestId) + userId + ":summary";
    }

    static String problem(long contestId, long userId, long problemId) {
        return userPrefix(contestId) + userId + ":problem:" + problemId;
    }

    static String processed(long contestId) {
        return PREFIX + contestId + ":processed";
    }

    static String userLock(long contestId, long userId) {
        return userPrefix(contestId) + userId + ":lock";
    }

    static String userPattern(long contestId) {
        return userPrefix(contestId) + "*";
    }

    static String problemPattern() {
        return PREFIX + "*:user:*:problem:*";
    }

    private static String userPrefix(long contestId) {
        return PREFIX + contestId + ":user:";
    }
}
