package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Repairs the non-authoritative MySQL staleness timestamp without moving the Redis checkpoint. */
@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
class ContestScoreboardAppliedAtCompletion {

    private final StringRedisTemplate redisTemplate;
    private final JdbcContestScoreboardAppliedAtWriter writer;
    private final int batchSize;

    ContestScoreboardAppliedAtCompletion(
            StringRedisTemplate redisTemplate,
            JdbcContestScoreboardAppliedAtWriter writer,
            ContestScoreboardStreamConsumerProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.writer = writer;
        this.batchSize = properties.effectiveBatchSize();
    }

    void complete(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }
        List<Long> ids = submissionIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        writer.markApplied(ids);
        redisTemplate.opsForSet().remove(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY,
                ids.stream().map(String::valueOf).toArray()
        );
    }

    void repairPending() {
        Set<String> rawIds = redisTemplate.opsForSet().members(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY
        );
        if (rawIds == null || rawIds.isEmpty()) {
            return;
        }
        List<Long> ids = rawIds.stream().map(Long::parseLong).sorted().toList();
        for (int start = 0; start < ids.size(); start += batchSize) {
            int end = Math.min(ids.size(), start + batchSize);
            complete(new ArrayList<>(ids.subList(start, end)));
        }
    }
}
