package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardReader;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.contest.scoreboard.ContestScoreboardSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
public class RedisContestScoreboardReader implements ContestScoreboardReader {

    private final ContestRedisKeyValueClient redisClient;

    public RedisContestScoreboardReader(ContestRedisKeyValueClient redisClient) {
        this.redisClient = redisClient;
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

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
