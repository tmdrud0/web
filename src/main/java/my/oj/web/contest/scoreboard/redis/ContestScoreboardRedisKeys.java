package my.oj.web.contest.scoreboard.redis;

final class ContestScoreboardRedisKeys {

    static final String OUTBOX_SEQUENCE = "contest:scoreboard:outbox:seq";
    static final String OUTBOX_SUBMISSION_SEQUENCE = "contest:scoreboard:outbox:submission";

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
