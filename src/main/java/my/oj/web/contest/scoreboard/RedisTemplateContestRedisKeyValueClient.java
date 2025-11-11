package my.oj.web.contest.scoreboard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard", name = "store", havingValue = "redis")
public class RedisTemplateContestRedisKeyValueClient implements ContestRedisKeyValueClient {

    private final StringRedisTemplate redisTemplate;
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('del', KEYS[1]) "
                            + "else return 0 end",
                    Long.class);

    public RedisTemplateContestRedisKeyValueClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    @Override
    public boolean deleteIfValueEquals(String key, String expectedValue) {
        if (expectedValue == null) {
            return false;
        }
        Long deleted = redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(key), expectedValue);
        return deleted != null && deleted > 0;
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public void delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
    }

    @Override
    public String hGet(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        return value != null ? value.toString() : null;
    }

    @Override
    public void hSet(String key, String field, String value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    @Override
    public long hIncrBy(String key, String field, long delta) {
        return redisTemplate.opsForHash().increment(key, field, delta);
    }

    @Override
    public Map<String, String> hGetAll(String key) {
        return redisTemplate.<String, String>opsForHash().entries(key);
    }

    @Override
    public void zAdd(String key, double score, String member) {
        redisTemplate.opsForZSet().add(key, member, score);
    }

    @Override
    public List<String> zRevRange(String key, long start, long end) {
        Set<String> members = redisTemplate.opsForZSet().reverseRange(key, start, end);
        if (members == null) {
            return List.of();
        }
        return new ArrayList<>(members);
    }

    @Override
    public Long zRevRank(String key, String member) {
        return redisTemplate.opsForZSet().reverseRank(key, member);
    }

    @Override
    public long zCard(String key) {
        Long size = redisTemplate.opsForZSet().zCard(key);
        return size != null ? size : 0L;
    }

    @Override
    public Set<String> scan(String pattern) {
        Set<String> result = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1000).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to scan Redis keys", e);
            }
            return keys;
        });
        return result != null ? result : new HashSet<>();
    }

    @Override
    public boolean sAdd(String key, String member) {
        Long added = redisTemplate.opsForSet().add(key, member);
        return added != null && added > 0;
    }

    @Override
    public boolean sIsMember(String key, String member) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, member));
    }
}
