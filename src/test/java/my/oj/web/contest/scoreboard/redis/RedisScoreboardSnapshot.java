package my.oj.web.contest.scoreboard.redis;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * An RDB restore of the scoreboard keys, done in place: {@link #take} {@code DUMP}s every scoreboard key
 * and {@link #restoreInto} atomically deletes the current ones and {@code RESTORE}s the dump - which is
 * what the scoreboard sees when Redis comes back from an older snapshot.
 */
public final class RedisScoreboardSnapshot {

    private static final String PATTERN = "contest:scoreboard:*";

    private final Map<byte[], byte[]> dumps;

    private RedisScoreboardSnapshot(Map<byte[], byte[]> dumps) {
        this.dumps = dumps;
    }

    public static RedisScoreboardSnapshot take(StringRedisTemplate redisTemplate) {
        return redisTemplate.execute((RedisCallback<RedisScoreboardSnapshot>) connection -> {
            Map<byte[], byte[]> dumps = new LinkedHashMap<>();
            for (byte[] key : keys(connection)) {
                byte[] value = connection.keyCommands().dump(key);
                if (value != null) {
                    dumps.put(key, value);
                }
            }
            return new RedisScoreboardSnapshot(dumps);
        });
    }

    /**
     * Deletes the current scoreboard keys and restores the dump in one {@code MULTI/EXEC}, so no reader
     * can observe the half-restored state in between - an RDB restore is atomic too, and a check that ran
     * between the delete and the restore would see an allocator of zero that no real restore produces.
     */
    public void restoreInto(StringRedisTemplate redisTemplate) {
        List<String> current = redisTemplate.execute((RedisCallback<List<String>>) connection ->
                keys(connection).stream().map(RedisScoreboardSnapshot::text).toList());
        redisTemplate.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public List<Object> execute(RedisOperations operations) {
                RedisOperations<String, String> redis = operations;
                redis.multi();
                if (!current.isEmpty()) {
                    redis.delete(current);
                }
                dumps.forEach((key, value) -> redis.restore(text(key), value, 0, TimeUnit.MILLISECONDS, true));
                return redis.exec();
            }
        });
    }

    public int keyCount() {
        return dumps.size();
    }

    private static List<byte[]> keys(RedisConnection connection) {
        List<byte[]> keys = new ArrayList<>();
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                ScanOptions.scanOptions().match(PATTERN).count(1000).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    static String text(byte[] key) {
        return new String(key, StandardCharsets.UTF_8);
    }
}
