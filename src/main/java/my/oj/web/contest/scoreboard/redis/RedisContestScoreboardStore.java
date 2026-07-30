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
import java.util.HashMap;
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
                                long contestSubmissionId,
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
            applyJudgement(contestSubmissionId, contestId, problemId, userId, contestStart, submittedTime, result);
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
        long solved = parseLong(summary.get(ContestScoreboardRedisFields.SUMMARY_SOLVED));
        long penalty = parseLong(summary.get(ContestScoreboardRedisFields.SUMMARY_PENALTY));
        return new ContestScoreboardEntry(userId, (int) solved, penalty);
    }

    /**
     * Records one attempt and rewrites what its problem contributes to the user summary.
     * The contribution is recomputed from every attempt stored for the problem, so applying
     * the same judgements in another order — or twice — reaches the same state. Mirrors
     * {@link ContestScoreboardRedisScript}.
     */
    private void applyJudgement(long contestSubmissionId,
                                long contestId,
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

        long contestMinutes = ContestScoreboardPolicy.computeContestMinutes(contestStart, submittedTime);
        ProblemAttempts attempts = ProblemAttempts.from(redisClient.hGetAll(problemKey));

        if (result == SubmissionResult.ACCEPTED) {
            if (attempts.recordAcceptedIfEarliest(contestMinutes, contestSubmissionId)) {
                redisClient.hSet(problemKey, ContestScoreboardRedisFields.ACCEPTED_MINUTES, Long.toString(contestMinutes));
                redisClient.hSet(problemKey, ContestScoreboardRedisFields.ACCEPTED_SUBMISSION_ID, Long.toString(contestSubmissionId));
            }
        } else {
            attempts.recordWrong(contestSubmissionId, contestMinutes);
            redisClient.hSet(
                    problemKey,
                    ContestScoreboardRedisFields.wrongAttempt(contestSubmissionId),
                    Long.toString(contestMinutes)
            );
        }

        ContestScoreboardPolicy.ProblemContribution contribution = attempts.contribution();
        long solvedDelta = contribution.solved() - attempts.contributedSolved();
        long penaltyDelta = contribution.penalty() - attempts.contributedPenalty();

        Map<String, String> summary = redisClient.hGetAll(summaryKey);
        long solved = parseLong(summary.get(ContestScoreboardRedisFields.SUMMARY_SOLVED));
        long penalty = parseLong(summary.get(ContestScoreboardRedisFields.SUMMARY_PENALTY));
        if (solvedDelta != 0L) {
            solved = redisClient.hIncrBy(summaryKey, ContestScoreboardRedisFields.SUMMARY_SOLVED, solvedDelta);
        }
        if (penaltyDelta != 0L) {
            penalty = redisClient.hIncrBy(summaryKey, ContestScoreboardRedisFields.SUMMARY_PENALTY, penaltyDelta);
        }
        if (solvedDelta != 0L || penaltyDelta != 0L) {
            redisClient.hSet(problemKey, ContestScoreboardRedisFields.CONTRIBUTED_SOLVED, Long.toString(contribution.solved()));
            redisClient.hSet(problemKey, ContestScoreboardRedisFields.CONTRIBUTED_PENALTY, Long.toString(contribution.penalty()));
        }
        updateRanking(rankingKey, userIdStr, solved, penalty, userId);
    }

    private void ensureUserInitialized(String rankingKey,
                                       String summaryKey,
                                       String userId,
                                       long userIdNumeric) {
        if (redisClient.hGet(summaryKey, ContestScoreboardRedisFields.SUMMARY_INITIALIZED) != null) {
            return;
        }
        redisClient.hSet(summaryKey, ContestScoreboardRedisFields.SUMMARY_SOLVED, "0");
        redisClient.hSet(summaryKey, ContestScoreboardRedisFields.SUMMARY_PENALTY, "0");
        redisClient.hSet(summaryKey, ContestScoreboardRedisFields.SUMMARY_INITIALIZED, ContestScoreboardRedisFields.INITIALIZED_FLAG);
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

    /** Every attempt stored for one (user, problem), plus what that problem last contributed. */
    private static final class ProblemAttempts {

        private final Map<Long, Long> wrongMinutesBySubmissionId = new HashMap<>();
        private Long acceptedMinutes;
        private Long acceptedSubmissionId;
        private long contributedSolved;
        private long contributedPenalty;

        private static ProblemAttempts from(Map<String, String> fields) {
            ProblemAttempts attempts = new ProblemAttempts();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                String name = field.getKey();
                switch (name) {
                    case ContestScoreboardRedisFields.ACCEPTED_MINUTES ->
                            attempts.acceptedMinutes = Long.parseLong(field.getValue());
                    case ContestScoreboardRedisFields.ACCEPTED_SUBMISSION_ID ->
                            attempts.acceptedSubmissionId = Long.parseLong(field.getValue());
                    case ContestScoreboardRedisFields.CONTRIBUTED_SOLVED ->
                            attempts.contributedSolved = Long.parseLong(field.getValue());
                    case ContestScoreboardRedisFields.CONTRIBUTED_PENALTY ->
                            attempts.contributedPenalty = Long.parseLong(field.getValue());
                    default -> {
                        if (name.startsWith(ContestScoreboardRedisFields.WRONG_PREFIX)) {
                            attempts.wrongMinutesBySubmissionId.put(
                                    Long.parseLong(name.substring(ContestScoreboardRedisFields.WRONG_PREFIX.length())),
                                    Long.parseLong(field.getValue())
                            );
                        }
                    }
                }
            }
            return attempts;
        }

        private boolean recordAcceptedIfEarliest(long contestMinutes, long contestSubmissionId) {
            if (acceptedMinutes != null && !ContestScoreboardPolicy.isEarlierAttempt(
                    contestMinutes,
                    contestSubmissionId,
                    acceptedMinutes,
                    acceptedSubmissionId
            )) {
                return false;
            }
            acceptedMinutes = contestMinutes;
            acceptedSubmissionId = contestSubmissionId;
            return true;
        }

        private void recordWrong(long contestSubmissionId, long contestMinutes) {
            wrongMinutesBySubmissionId.put(contestSubmissionId, contestMinutes);
        }

        private ContestScoreboardPolicy.ProblemContribution contribution() {
            return ContestScoreboardPolicy.computeProblemContribution(
                    acceptedMinutes,
                    acceptedSubmissionId,
                    wrongMinutesBySubmissionId
            );
        }

        private long contributedSolved() {
            return contributedSolved;
        }

        private long contributedPenalty() {
            return contributedPenalty;
        }
    }
}
