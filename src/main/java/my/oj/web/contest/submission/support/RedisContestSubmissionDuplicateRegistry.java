package my.oj.web.contest.submission.support;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class RedisContestSubmissionDuplicateRegistry implements ContestSubmissionDuplicateRegistry {

    private static final String KEY_PREFIX = "contest:submission:dedup:";
    private static final String USER_SEGMENT = ":user:";
    private static final String PROBLEM_SEGMENT = ":problem:";
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;

    public RedisContestSubmissionDuplicateRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<Long> findDuplicateSubmissionId(long contestId, long problemId, long userId, String codeHash) {
        Object value = redisTemplate.opsForHash().get(dedupKey(contestId, userId, problemId), codeHash);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.toString()));
        } catch (NumberFormatException ex) {
            redisTemplate.opsForHash().delete(dedupKey(contestId, userId, problemId), codeHash);
            return Optional.empty();
        }
    }

    @Override
    public void registerSubmission(long contestId, long problemId, long userId, String codeHash, long submissionId) {
        String key = dedupKey(contestId, userId, problemId);
        redisTemplate.opsForHash().put(key, codeHash, Long.toString(submissionId));
        redisTemplate.expire(key, KEY_TTL);
    }

    @Override
    public void purgeContest(long contestId) {
        Set<String> keys = scanKeys(KEY_PREFIX + contestId + "*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> scannedKeys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1000).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    scannedKeys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to scan submission dedup keys", e);
            }
            return scannedKeys;
        });
        return keys != null ? keys : new HashSet<>();
    }

    private String dedupKey(long contestId, long userId, long problemId) {
        return KEY_PREFIX + contestId + USER_SEGMENT + userId + PROBLEM_SEGMENT + problemId;
    }
}
