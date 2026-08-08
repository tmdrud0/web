package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Estimates the number of unbounded {@code w:*} fields away from the scrape path.
 *
 * <p>The key scan is incremental, and only a stable hash-ordered sample reaches {@code HKEYS}.
 * Reading every field was measured in seconds at load-test size and perturbed the system being
 * observed. The bounded sample caps the number of hashes inspected; fields inside a sampled hash
 * can still grow with attempts, so the scheduled timer keeps that remaining cost visible.
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.redis.metrics.wrong-fields",
        name = "enabled",
        havingValue = "true"
)
public class RedisContestScoreboardWrongAttemptMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(RedisContestScoreboardWrongAttemptMetrics.class);
    private static final String WRONG_FIELD_PREFIX = "w:";
    static final int MAX_SAMPLED_PROBLEM_HASHES = 1_000;

    private final ContestRedisKeyValueClient redisClient;
    private final AtomicLong wrongAttemptFields = new AtomicLong();
    private volatile Counter pollFailures = pollFailureCounter(new CompositeMeterRegistry());
    private volatile Timer pollLatency = pollLatencyTimer(new CompositeMeterRegistry());

    public RedisContestScoreboardWrongAttemptMetrics(ContestRedisKeyValueClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("contest.scoreboard.redis.wrong.attempt", wrongAttemptFields, AtomicLong::get)
                .baseUnit("fields")
                .description("Estimated total w:* fields retained in Redis scoreboard problem "
                        + "hashes, based on a stable bounded hash sample")
                .register(registry);
        this.pollFailures = pollFailureCounter(registry);
        this.pollLatency = pollLatencyTimer(registry);
    }

    @Scheduled(fixedDelayString = "${contest.scoreboard.redis.metrics.wrong-fields.poll-interval-ms:60000}")
    public void poll() {
        long startedNanos = System.nanoTime();
        try {
            Set<String> problemKeys = redisClient.scan(ContestScoreboardRedisKeys.problemPattern());
            List<String> sampledKeys = problemKeys.stream()
                    .sorted(Comparator.comparingInt(String::hashCode)
                            .thenComparing(Comparator.naturalOrder()))
                    .limit(MAX_SAMPLED_PROBLEM_HASHES)
                    .toList();
            long sampledFields = redisClient.countHashFieldsWithPrefix(sampledKeys, WRONG_FIELD_PREFIX);
            long estimatedFields = sampledKeys.size() == problemKeys.size()
                    ? sampledFields
                    : Math.round((double) sampledFields * problemKeys.size() / sampledKeys.size());
            wrongAttemptFields.set(estimatedFields);
        } catch (RuntimeException failure) {
            pollFailures.increment();
            log.warn("Failed to count Redis scoreboard w:* fields; gauge keeps its previous value", failure);
        } finally {
            pollLatency.record(Duration.ofNanos(System.nanoTime() - startedNanos));
        }
    }

    long currentWrongAttemptFields() {
        return wrongAttemptFields.get();
    }

    private static Counter pollFailureCounter(MeterRegistry registry) {
        return Counter.builder("contest.scoreboard.redis.wrong.attempt.poll.failures")
                .description("Failures while polling Redis scoreboard w:* fields; the cached "
                        + "gauge retains its previous value")
                .register(registry);
    }

    private static Timer pollLatencyTimer(MeterRegistry registry) {
        return Timer.builder("contest.scoreboard.redis.wrong.attempt.poll")
                .description("Duration of the scheduled bounded Redis w:* field estimate, kept off "
                        + "the Prometheus scrape thread")
                .serviceLevelObjectives(
                        Duration.ofMillis(10),
                        Duration.ofMillis(25),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5)
                )
                .register(registry);
    }
}
