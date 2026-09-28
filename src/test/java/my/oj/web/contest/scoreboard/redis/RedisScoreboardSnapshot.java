package my.oj.web.contest.scoreboard.redis;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An RDB restore of the scoreboard keys, done in place: {@link #take} {@code DUMP}s every scoreboard key
 * and {@link #restoreInto} deletes the current ones and {@code RESTORE}s the dump - which is what the
 * scoreboard sees when Redis comes back from an older snapshot.
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

    public void restoreInto(StringRedisTemplate redisTemplate) {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            List<byte[]> current = keys(connection);
            if (!current.isEmpty()) {
                connection.keyCommands().del(current.toArray(new byte[0][]));
            }
            dumps.forEach((key, value) -> connection.keyCommands().restore(key, 0, value, true));
            return null;
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
