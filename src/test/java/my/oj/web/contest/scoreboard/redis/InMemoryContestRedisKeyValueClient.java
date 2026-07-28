package my.oj.web.contest.scoreboard.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enough of Redis to exercise the reader's slicing and the applier's reset without a server.
 */
final class InMemoryContestRedisKeyValueClient implements ContestRedisKeyValueClient {

    private final Map<String, String> strings = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> zsets = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        return strings.get(key);
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return strings.putIfAbsent(key, value) == null;
    }

    @Override
    public boolean deleteIfValueEquals(String key, String expectedValue) {
        return strings.remove(key, expectedValue);
    }

    @Override
    public void delete(String key) {
        strings.remove(key);
        hashes.remove(key);
        zsets.remove(key);
        sets.remove(key);
    }

    @Override
    public void delete(Collection<String> keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            delete(key);
        }
    }

    @Override
    public String hGet(String key, String field) {
        Map<String, String> map = hashes.get(key);
        return map != null ? map.get(field) : null;
    }

    @Override
    public void hSet(String key, String field, String value) {
        hashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
    }

    @Override
    public long hIncrBy(String key, String field, long delta) {
        Map<String, String> map = hashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        long next = Long.parseLong(map.getOrDefault(field, "0")) + delta;
        map.put(field, Long.toString(next));
        return next;
    }

    @Override
    public Map<String, String> hGetAll(String key) {
        Map<String, String> map = hashes.get(key);
        if (map == null) {
            return Map.of();
        }
        return new HashMap<>(map);
    }

    @Override
    public void zAdd(String key, double score, String member) {
        zsets.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(member, score);
    }

    @Override
    public List<String> zRevRange(String key, long start, long end) {
        List<Map.Entry<String, Double>> sorted = sortedEntries(key);
        if (sorted.isEmpty()) {
            return List.of();
        }

        int fromIndex = (int) Math.max(0, start);
        int toIndex;
        if (end < 0) {
            toIndex = sorted.size();
        } else {
            toIndex = (int) Math.min(sorted.size(), end + 1);
        }
        if (fromIndex >= toIndex) {
            return List.of();
        }
        List<String> slice = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            slice.add(sorted.get(i).getKey());
        }
        return slice;
    }

    @Override
    public Long zRevRank(String key, String member) {
        List<Map.Entry<String, Double>> sorted = sortedEntries(key);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getKey().equals(member)) {
                return (long) i;
            }
        }
        return null;
    }

    @Override
    public long zCard(String key) {
        Map<String, Double> members = zsets.get(key);
        return members != null ? members.size() : 0L;
    }

    @Override
    public Set<String> scan(String pattern) {
        Set<String> keys = new HashSet<>();
        keys.addAll(filter(keysOf(strings), pattern));
        keys.addAll(filter(keysOf(hashes), pattern));
        keys.addAll(filter(keysOf(zsets), pattern));
        keys.addAll(filter(keysOf(sets), pattern));
        return keys;
    }

    @Override
    public boolean sAdd(String key, String member) {
        return sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(member);
    }

    @Override
    public boolean sIsMember(String key, String member) {
        Set<String> members = sets.get(key);
        return members != null && members.contains(member);
    }

    private List<Map.Entry<String, Double>> sortedEntries(String key) {
        Map<String, Double> members = zsets.get(key);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(members.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry<String, Double>::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));
        return sorted;
    }

    private Set<String> filter(Set<String> keys, String pattern) {
        if (pattern == null || pattern.equals("*")) {
            return keys;
        }
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            Set<String> matched = new HashSet<>();
            for (String key : keys) {
                if (key.startsWith(prefix)) {
                    matched.add(key);
                }
            }
            return matched;
        }
        return keys.contains(pattern) ? Set.of(pattern) : Set.of();
    }

    private Set<String> keysOf(Map<String, ?> map) {
        if (map.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(map.keySet());
    }
}
