package my.oj.web.contest.scoreboard;

import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
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

import static org.assertj.core.api.Assertions.assertThat;

class RedisContestScoreboardStoreTests {

    private RedisContestScoreboardStore store;

    @BeforeEach
    void setUp() {
        store = new RedisContestScoreboardStore(new InMemoryRedisClient());
    }

    @Test
    void recordJudgementBuildsSortedRanking() {
        long contestId = 7L;
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 9, 0);

        store.recordJudgement(1L, contestId, 11L, 1001L, start, start.plusMinutes(15), SubmissionResult.WRONG_ANSWER);
        store.recordJudgement(2L, contestId, 11L, 1001L, start, start.plusMinutes(25), SubmissionResult.ACCEPTED);
        store.recordJudgement(3L, contestId, 12L, 2002L, start, start.plusMinutes(5), SubmissionResult.ACCEPTED);

        List<ContestScoreboardEntry> ranking = store.currentRanking(contestId);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0)).isEqualTo(new ContestScoreboardEntry(2002L, 1, 5));
        assertThat(ranking.get(1)).isEqualTo(new ContestScoreboardEntry(1001L, 1, 30));
    }

    @Test
    void resetRemovesContestState() {
        long contestId = 9L;
        LocalDateTime start = LocalDateTime.of(2024, 5, 1, 10, 0);

        store.recordJudgement(10L, contestId, 21L, 3333L, start, start.plusMinutes(12), SubmissionResult.ACCEPTED);
        assertThat(store.currentRanking(contestId)).isNotEmpty();

        store.reset(contestId);

        assertThat(store.currentRanking(contestId)).isEmpty();
        assertThat(store.snapshot(contestId).entries()).isEmpty();
    }

    @Test
    void duplicateEventDoesNotAffectRanking() {
        long contestId = 13L;
        LocalDateTime start = LocalDateTime.of(2024, 7, 1, 9, 0);

        store.recordJudgement(100L, contestId, 41L, 5555L, start, start.plusMinutes(4), SubmissionResult.WRONG_ANSWER);
        store.recordJudgement(101L, contestId, 41L, 5555L, start, start.plusMinutes(7), SubmissionResult.ACCEPTED);

        List<ContestScoreboardEntry> before = store.currentRanking(contestId);

        store.recordJudgement(101L, contestId, 41L, 5555L, start, start.plusMinutes(7), SubmissionResult.ACCEPTED);
        store.recordJudgement(100L, contestId, 41L, 5555L, start, start.plusMinutes(4), SubmissionResult.WRONG_ANSWER);

        assertThat(store.currentRanking(contestId)).containsExactlyElementsOf(before);
    }

    @Test
    void topRankingRespectsRequestedSize() {
        long contestId = 21L;
        LocalDateTime start = LocalDateTime.of(2024, 8, 1, 9, 0);

        for (int i = 1; i <= 5; i++) {
            long userId = i;
            store.recordJudgement(1000L + i, contestId, 90L + i, userId, start, start.plusMinutes(i * 5L), SubmissionResult.ACCEPTED);
        }

        ContestScoreboardSlice topThree = store.topRanking(contestId, 3);

        assertThat(topThree.startRank()).isEqualTo(1);
        assertThat(topThree.entries()).hasSize(3);
        assertThat(topThree.totalParticipants()).isEqualTo(5);
        assertThat(topThree.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void sliceReturnsRequestedWindow() {
        long contestId = 23L;
        LocalDateTime start = LocalDateTime.of(2024, 8, 3, 9, 0);

        for (int i = 1; i <= 8; i++) {
            long userId = i;
            store.recordJudgement(3000L + i, contestId, 200L + i, userId, start, start.plusMinutes(i * 2L), SubmissionResult.ACCEPTED);
        }

        ContestScoreboardSlice slice = store.slice(contestId, 4, 3);

        assertThat(slice.startRank()).isEqualTo(4);
        assertThat(slice.entries()).hasSize(3);
        assertThat(slice.totalParticipants()).isEqualTo(8);
        assertThat(slice.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(4L, 5L, 6L);
    }

    @Test
    void rankingAroundUserCentersWindowWhenPossible() {
        long contestId = 22L;
        LocalDateTime start = LocalDateTime.of(2024, 8, 2, 9, 0);

        for (int i = 1; i <= 6; i++) {
            long userId = i;
            store.recordJudgement(2000L + i, contestId, 100L + i, userId, start, start.plusMinutes(i * 3L), SubmissionResult.ACCEPTED);
        }

        var aroundUser = store.rankingAroundUser(contestId, 4L, 3).orElseThrow();

        assertThat(aroundUser.startRank()).isEqualTo(3);
        assertThat(aroundUser.entries()).hasSize(3);
        assertThat(aroundUser.entries().stream().map(ContestScoreboardEntry::userId))
                .containsExactly(3L, 4L, 5L);
    }

    private static final class InMemoryRedisClient implements ContestRedisKeyValueClient {

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
}
