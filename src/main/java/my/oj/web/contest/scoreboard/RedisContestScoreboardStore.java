package my.oj.web.contest.scoreboard;

import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
public class RedisContestScoreboardStore implements ContestScoreboardStore {

    private static final String KEY_PREFIX = "contest:scoreboard:";
    private static final String RANKING_SUFFIX = ":ranking";
    private static final String USER_SEGMENT = ":user:";
    private static final String SUMMARY_SUFFIX = ":summary";
    private static final String PROBLEM_SEGMENT = ":problem:";
    private static final String LOCK_SUFFIX = ":lock";
    private static final String PROCESSED_SUFFIX = ":processed";

    private final ContestRedisKeyValueClient redisClient;

    public RedisContestScoreboardStore(ContestRedisKeyValueClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public void recordJudgement(long eventId,
                                long contestId,
                                long problemId,
                                long userId,
                                LocalDateTime contestStart,
                                LocalDateTime submittedTime,
                                SubmissionResult result) {
        if (result == SubmissionResult.PENDING) {
            return;
        }

        executeWithLock(contestId, userId, () -> {
            if (isProcessed(contestId, eventId)) {
                return;
            }
            applyJudgement(contestId, problemId, userId, contestStart, submittedTime, result);
            markProcessed(contestId, eventId);
        });
    }

    @Override
    public ContestScoreboardSnapshot snapshot(long contestId) {
        return new ContestScoreboardSnapshot(contestId, currentRanking(contestId));
    }

    @Override
    public List<ContestScoreboardEntry> currentRanking(long contestId) {
        List<String> userIds = redisClient.zRevRange(rankingKey(contestId), 0, -1);
        if (userIds.isEmpty()) {
            return List.of();
        }

        return userIds.stream()
                .map(userId -> toEntry(contestId, userId))
                .toList();
    }

    @Override
    public void reset(long contestId) {
        Set<String> keys = new HashSet<>(redisClient.scan(KEY_PREFIX + contestId + USER_SEGMENT + "*"));
        keys.addAll(redisClient.scan(userLockPrefix(contestId) + "*"));
        keys.add(rankingKey(contestId));
        keys.add(processedKey(contestId));
        if (!keys.isEmpty()) {
            redisClient.delete(keys);
        }
    }

    private ContestScoreboardEntry toEntry(long contestId, String userIdStr) {
        long userId = Long.parseLong(userIdStr);
        Map<String, String> summary = redisClient.hGetAll(summaryKey(contestId, userId));
        long solved = parseLong(summary.get(ContestScoreboardSummaryFields.SOLVED));
        long penalty = parseLong(summary.get(ContestScoreboardSummaryFields.PENALTY));
        return new ContestScoreboardEntry(userId, (int) solved, penalty);
    }

    private void applyJudgement(long contestId,
                                long problemId,
                                long userId,
                                LocalDateTime contestStart,
                                LocalDateTime submittedTime,
                                SubmissionResult result) {
        String rankingKey = rankingKey(contestId);
        String summaryKey = summaryKey(contestId, userId);
        String problemKey = problemKey(contestId, userId, problemId);
        String userIdStr = Long.toString(userId);

        ensureUserInitialized(rankingKey, summaryKey, userIdStr, userId);

        String accepted = redisClient.hGet(problemKey, ContestScoreboardProblemFields.ACCEPTED);
        if (ContestScoreboardProblemFields.ACCEPTED_FLAG.equals(accepted)) {
            return;
        }

        if (result == SubmissionResult.ACCEPTED) {
            long wrongAttempts = parseLong(redisClient.hGet(problemKey, ContestScoreboardProblemFields.WRONG_ATTEMPTS));
            long penaltyIncrement = ContestScoreboardMath.computePenalty(contestStart, submittedTime, wrongAttempts);

            redisClient.hSet(problemKey, ContestScoreboardProblemFields.ACCEPTED, ContestScoreboardProblemFields.ACCEPTED_FLAG);
            redisClient.hSet(problemKey, ContestScoreboardProblemFields.WRONG_ATTEMPTS, Long.toString(wrongAttempts));

            long solved = redisClient.hIncrBy(summaryKey, ContestScoreboardSummaryFields.SOLVED, 1L);
            long penalty = redisClient.hIncrBy(summaryKey, ContestScoreboardSummaryFields.PENALTY, penaltyIncrement);
            updateRanking(rankingKey, userIdStr, solved, penalty, userId);
        } else {
            redisClient.hSet(problemKey, ContestScoreboardProblemFields.ACCEPTED, ContestScoreboardProblemFields.NOT_ACCEPTED_FLAG);
            redisClient.hIncrBy(problemKey, ContestScoreboardProblemFields.WRONG_ATTEMPTS, 1L);
            Map<String, String> summary = redisClient.hGetAll(summaryKey);
            long solved = parseLong(summary.get(ContestScoreboardSummaryFields.SOLVED));
            long penalty = parseLong(summary.get(ContestScoreboardSummaryFields.PENALTY));
            updateRanking(rankingKey, userIdStr, solved, penalty, userId);
        }
    }

    private void ensureUserInitialized(String rankingKey,
                                       String summaryKey,
                                       String userId,
                                       long userIdNumeric) {
        if (redisClient.hGet(summaryKey, ContestScoreboardSummaryFields.INITIALIZED) != null) {
            return;
        }
        redisClient.hSet(summaryKey, ContestScoreboardSummaryFields.SOLVED, "0");
        redisClient.hSet(summaryKey, ContestScoreboardSummaryFields.PENALTY, "0");
        redisClient.hSet(summaryKey, ContestScoreboardSummaryFields.INITIALIZED, ContestScoreboardProblemFields.ACCEPTED_FLAG);
        updateRanking(rankingKey, userId, 0L, 0L, userIdNumeric);
    }

    private void updateRanking(String rankingKey,
                               String userId,
                               long solved,
                               long penalty,
                               long userIdNumeric) {
        double score = ContestScoreboardMath.computeScore(solved, penalty, userIdNumeric);
        redisClient.zAdd(rankingKey, score, userId);
    }

    private void executeWithLock(long contestId, long userId, Runnable action) {
        String lockKey = userLockKey(contestId, userId);
        long backoff = ContestScoreboardConstants.INITIAL_BACKOFF_MILLIS;

        for (int attempt = 0; attempt < ContestScoreboardConstants.MAX_LOCK_ATTEMPTS; attempt++) {
            String token = Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
            boolean acquired = redisClient.setIfAbsent(lockKey, token, ContestScoreboardConstants.LOCK_TTL);
            if (acquired) {
                try {
                    action.run();
                } finally {
                    String current = redisClient.get(lockKey);
                    if (token.equals(current)) {
                        redisClient.delete(lockKey);
                    }
                }
                return;
            }

            sleep(backoff + ThreadLocalRandom.current().nextLong(backoff + 1));
            backoff = Math.min(backoff * 2, ContestScoreboardConstants.MAX_BACKOFF_MILLIS);
        }
        throw new IllegalStateException("Failed to acquire Redis scoreboard lock for contest " + contestId + " user " + userId);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring Redis lock", e);
        }
    }

    private boolean isProcessed(long contestId, long eventId) {
        return redisClient.sIsMember(processedKey(contestId), Long.toString(eventId));
    }

    private void markProcessed(long contestId, long eventId) {
        redisClient.sAdd(processedKey(contestId), Long.toString(eventId));
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private String rankingKey(long contestId) {
        return KEY_PREFIX + contestId + RANKING_SUFFIX;
    }

    private String summaryKey(long contestId, long userId) {
        return KEY_PREFIX + contestId + USER_SEGMENT + userId + SUMMARY_SUFFIX;
    }

    private String problemKey(long contestId, long userId, long problemId) {
        return KEY_PREFIX + contestId + USER_SEGMENT + userId + PROBLEM_SEGMENT + problemId;
    }

    private String userLockKey(long contestId, long userId) {
        return userLockPrefix(contestId) + userId + LOCK_SUFFIX;
    }

    private String userLockPrefix(long contestId) {
        return KEY_PREFIX + contestId + USER_SEGMENT;
    }

    private String processedKey(long contestId) {
        return KEY_PREFIX + contestId + PROCESSED_SUFFIX;
    }

    private static final class ContestScoreboardSummaryFields {
        private static final String INITIALIZED = "initialized";
        private static final String SOLVED = "solved";
        private static final String PENALTY = "penalty";
    }

    private static final class ContestScoreboardProblemFields {
        private static final String WRONG_ATTEMPTS = "wrongAttempts";
        private static final String ACCEPTED = "accepted";
        private static final String ACCEPTED_FLAG = "1";
        private static final String NOT_ACCEPTED_FLAG = "0";
    }
}
