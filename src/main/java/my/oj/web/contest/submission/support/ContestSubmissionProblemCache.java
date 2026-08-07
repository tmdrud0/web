package my.oj.web.contest.submission.support;

import my.oj.web.contest.Contest;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ContestSubmissionProblemCache {

    private final ProblemRepository problemRepository;
    private final ConcurrentMap<Long, CacheEntry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxEntries;
    private final Clock clock;

    @Autowired
    public ContestSubmissionProblemCache(
            ProblemRepository problemRepository,
            @Value("${contest.submission.problem-cache.ttl-seconds:60}") long ttlSeconds,
            @Value("${contest.submission.problem-cache.max-entries:10000}") int maxEntries
    ) {
        this(problemRepository, ttlSeconds, maxEntries, Clock.systemDefaultZone());
    }

    ContestSubmissionProblemCache(ProblemRepository problemRepository,
                                  long ttlSeconds,
                                  int maxEntries,
                                  Clock clock) {
        this.problemRepository = problemRepository;
        this.ttlMillis = Duration.ofSeconds(Math.max(1L, ttlSeconds)).toMillis();
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
    }

    public Optional<Problem> findById(long problemId) {
        long nowMillis = clock.millis();
        LocalDateTime now = LocalDateTime.now(clock);
        ensureCapacity(problemId, nowMillis, now);
        CacheEntry entry = entries.compute(problemId, (id, cached) -> {
            if (cached != null && !cached.isExpired(nowMillis, now)) {
                return cached;
            }
            return load(id, nowMillis);
        });
        return entry.problem();
    }

    public void evictProblem(long problemId) {
        entries.remove(problemId);
    }

    public void evictContest(long contestId) {
        entries.entrySet().removeIf(entry -> entry.getValue().contestId() == contestId);
    }

    public void evictAll() {
        entries.clear();
    }

    private CacheEntry load(long problemId, long nowMillis) {
        Optional<Problem> problem = problemRepository.findWithContestById(problemId);
        long contestId = problem
                .map(Problem::getContest)
                .map(Contest::getId)
                .orElse(-1L);
        LocalDateTime contestEnd = problem
                .map(Problem::getContest)
                .map(Contest::getEndTime)
                .orElse(null);
        return new CacheEntry(problem, contestId, contestEnd, nowMillis + ttlMillis);
    }

    private void ensureCapacity(long loadingProblemId, long nowMillis, LocalDateTime now) {
        if (entries.containsKey(loadingProblemId) || entries.size() < maxEntries) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry.getValue().isExpired(nowMillis, now));
        if (entries.size() < maxEntries) {
            return;
        }
        entries.keySet().stream().findFirst().ifPresent(entries::remove);
    }

    private record CacheEntry(
            Optional<Problem> problem,
            long contestId,
            LocalDateTime contestEnd,
            long expiresAtMillis
    ) {
        private boolean isExpired(long nowMillis, LocalDateTime now) {
            return nowMillis >= expiresAtMillis
                    || (contestEnd != null && now.isAfter(contestEnd));
        }
    }
}
