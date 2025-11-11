package my.oj.web.contest.scoreboard;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ContestRedisKeyValueClient {

    String get(String key);

    boolean setIfAbsent(String key, String value, Duration ttl);

    boolean deleteIfValueEquals(String key, String expectedValue);

    void delete(String key);

    void delete(Collection<String> keys);

    String hGet(String key, String field);

    void hSet(String key, String field, String value);

    long hIncrBy(String key, String field, long delta);

    Map<String, String> hGetAll(String key);

    void zAdd(String key, double score, String member);

    List<String> zRevRange(String key, long start, long end);

    Long zRevRank(String key, String member);

    long zCard(String key);

    Set<String> scan(String pattern);

    boolean sAdd(String key, String member);

    boolean sIsMember(String key, String member);
}
