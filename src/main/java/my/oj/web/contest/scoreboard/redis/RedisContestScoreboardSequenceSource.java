package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the sequence mapping the Lua apply script writes.
 *
 * <p>One {@code HMGET} per batch, in the order the ids were asked for, so persisting a batch's
 * sequences costs one round trip rather than one per result.
 */
public class RedisContestScoreboardSequenceSource implements ContestScoreboardSequenceSource {

    public static final String SUBMISSION_SEQUENCE_KEY = RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY;

    public static final String SEQUENCE_KEY = RedisContestScoreboardApplier.SEQUENCE_KEY;

    private final StringRedisTemplate redisTemplate;

    public RedisContestScoreboardSequenceSource(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Map<Long, Long> appliedSequences(Collection<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Map.of();
        }
        List<Long> orderedIds = submissionIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (orderedIds.isEmpty()) {
            return Map.of();
        }
        List<String> fields = orderedIds.stream().map(String::valueOf).toList();
        List<String> values = redisTemplate.<String, String>opsForHash()
                .multiGet(SUBMISSION_SEQUENCE_KEY, fields);
        Map<Long, Long> sequences = new LinkedHashMap<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            String value = values.get(index);
            if (value != null && !value.isBlank()) {
                sequences.put(orderedIds.get(index), Long.parseLong(value));
            }
        }
        return sequences;
    }

    @Override
    public long allocatorSequence() {
        String value = redisTemplate.opsForValue().get(SEQUENCE_KEY);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "The scoreboard sequence allocator holds a non-numeric value: " + value, exception);
        }
        if (parsed < 0) {
            throw new IllegalStateException(
                    "The scoreboard sequence allocator holds a negative value: " + parsed);
        }
        return parsed;
    }

    @Override
    public long mappedSubmissionCount() {
        Long size = redisTemplate.opsForHash().size(SUBMISSION_SEQUENCE_KEY);
        return size == null ? 0L : size;
    }
}
