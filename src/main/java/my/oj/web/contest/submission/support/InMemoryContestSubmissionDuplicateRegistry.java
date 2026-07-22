package my.oj.web.contest.submission.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(prefix = "contest.submission.dedup", name = "store", havingValue = "memory", matchIfMissing = true)
public class InMemoryContestSubmissionDuplicateRegistry implements ContestSubmissionDuplicateRegistry {

    private final ConcurrentMap<DedupKey, CachedSubmission> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public InMemoryContestSubmissionDuplicateRegistry(
            @Value("${contest.submission.dedup.memory.ttl-seconds:172800}") long ttlSeconds
    ) {
        long normalized = Math.max(1L, ttlSeconds);
        this.ttlMillis = Duration.ofSeconds(normalized).toMillis();
    }

    @Override
    public Optional<Long> findDuplicateSubmissionId(long contestId, long problemId, long userId, String codeHash) {
        DedupKey key = new DedupKey(contestId, problemId, userId, codeHash);
        CachedSubmission cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (isExpired(cached.expiresAtMillis())) {
            cache.remove(key, cached);
            return Optional.empty();
        }
        return Optional.of(cached.submissionId());
    }

    @Override
    public void registerSubmission(long contestId, long problemId, long userId, String codeHash, long submissionId) {
        DedupKey key = new DedupKey(contestId, problemId, userId, codeHash);
        cache.put(key, new CachedSubmission(submissionId, System.currentTimeMillis() + ttlMillis));
    }

    @Override
    public void purgeContest(long contestId) {
        cache.entrySet().removeIf(entry -> entry.getKey().contestId() == contestId);
    }

    private boolean isExpired(long expiresAtMillis) {
        return System.currentTimeMillis() > expiresAtMillis;
    }

    private record DedupKey(long contestId, long problemId, long userId, String codeHash) {
    }

    private record CachedSubmission(long submissionId, long expiresAtMillis) {
    }
}
