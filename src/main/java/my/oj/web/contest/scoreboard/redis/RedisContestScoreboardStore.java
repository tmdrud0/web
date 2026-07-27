package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.contest.scoreboard.ContestScoreboardSnapshot;
import my.oj.web.contest.scoreboard.ContestScoreboardStore;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
public class RedisContestScoreboardStore implements ContestScoreboardStore {

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final long INITIAL_BACKOFF_MILLIS = 10L;
    private static final long MAX_BACKOFF_MILLIS = 200L;
    private static final int MAX_LOCK_ATTEMPTS = 6;

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
        return new ContestScoreboardSnapshot(contestId, slice(contestId, 1, Integer.MAX_VALUE).entries());
    }

    @Override
    public ContestScoreboardSlice slice(long contestId, long startRank, int size) {
        long total = totalParticipants(contestId);
        long normalizedStart = Math.max(1, startRank);
        if (size <= 0 || total == 0 || normalizedStart > total) {
            return new ContestScoreboardSlice(contestId, normalizedStart, List.of(), total);
        }

        long startIndex = normalizedStart - 1;
        long endIndex = Math.min(total - 1, startIndex + size - 1);
        List<String> userIds = redisClient.zRevRange(ContestScoreboardRedisKeys.ranking(contestId), startIndex, endIndex);
        return new ContestScoreboardSlice(contestId, normalizedStart, toEntries(contestId, userIds), total);
    }

    @Override
    public Optional<ContestScoreboardSlice> rankingAroundUser(long contestId, long userId, int windowSize) {
        long total = totalParticipants(contestId);
        if (total == 0) {
            return Optional.empty();
        }
        int effectiveWindow = Math.max(1, Math.min(windowSize, (int) Math.min(total, Integer.MAX_VALUE)));
        String rankingKey = ContestScoreboardRedisKeys.ranking(contestId);
        Long rankIndex = redisClient.zRevRank(rankingKey, Long.toString(userId));
        if (rankIndex == null) {
            return Optional.empty();
        }

        long startIndex = Math.max(0, rankIndex - effectiveWindow / 2L);
        long lastIndex = startIndex + effectiveWindow - 1L;
        if (lastIndex >= total) {
            lastIndex = total - 1;
            startIndex = Math.max(0, total - effectiveWindow);
        }

        List<String> userIds = redisClient.zRevRange(rankingKey, startIndex, lastIndex);
        long startRank = startIndex + 1;
        return Optional.of(new ContestScoreboardSlice(contestId, startRank, toEntries(contestId, userIds), total));
    }

    @Override
    public long totalParticipants(long contestId) {
        return redisClient.zCard(ContestScoreboardRedisKeys.ranking(contestId));
    }

    @Override
    public void reset(long contestId) {
        Set<String> keys = new HashSet<>(redisClient.scan(ContestScoreboardRedisKeys.userPattern(contestId)));
        keys.add(ContestScoreboardRedisKeys.ranking(contestId));
        keys.add(ContestScoreboardRedisKeys.processed(contestId));
        if (!keys.isEmpty()) {
            redisClient.delete(keys);
        }
    }

    private List<ContestScoreboardEntry> toEntries(long contestId, List<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userIds.stream()
                .map(userId -> toEntry(contestId, userId))
                .toList();
    }

    private ContestScoreboardEntry toEntry(long contestId, String userIdStr) {
        long userId = Long.parseLong(userIdStr);
        Map<String, String> summary = redisClient.hGetAll(ContestScoreboardRedisKeys.summary(contestId, userId));
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
        String rankingKey = ContestScoreboardRedisKeys.ranking(contestId);
        String summaryKey = ContestScoreboardRedisKeys.summary(contestId, userId);
        String problemKey = ContestScoreboardRedisKeys.problem(contestId, userId, problemId);
        String userIdStr = Long.toString(userId);

        ensureUserInitialized(rankingKey, summaryKey, userIdStr, userId);

        String accepted = redisClient.hGet(problemKey, ContestScoreboardProblemFields.ACCEPTED);
        if (ContestScoreboardProblemFields.ACCEPTED_FLAG.equals(accepted)) {
            return;
        }

        if (result == SubmissionResult.ACCEPTED) {
            long wrongAttempts = parseLong(redisClient.hGet(problemKey, ContestScoreboardProblemFields.WRONG_ATTEMPTS));
            long penaltyIncrement = ContestScoreboardPolicy.computePenalty(
                    contestStart,
                    submittedTime,
                    wrongAttempts
            );

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
        double score = ContestScoreboardPolicy.computeScore(solved, penalty, userIdNumeric);
        redisClient.zAdd(rankingKey, score, userId);
    }

    private void executeWithLock(long contestId, long userId, Runnable action) {
        String lockKey = ContestScoreboardRedisKeys.userLock(contestId, userId);
        long backoff = INITIAL_BACKOFF_MILLIS;

        for (int attempt = 0; attempt < MAX_LOCK_ATTEMPTS; attempt++) {
            String token = Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
            boolean acquired = redisClient.setIfAbsent(lockKey, token, LOCK_TTL);
            if (acquired) {
                try {
                    action.run();
                } finally {
                    redisClient.deleteIfValueEquals(lockKey, token);
                }
                return;
            }

            sleep(backoff + ThreadLocalRandom.current().nextLong(backoff + 1));
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
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
        return redisClient.sIsMember(ContestScoreboardRedisKeys.processed(contestId), Long.toString(eventId));
    }

    private void markProcessed(long contestId, long eventId) {
        redisClient.sAdd(ContestScoreboardRedisKeys.processed(contestId), Long.toString(eventId));
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
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
