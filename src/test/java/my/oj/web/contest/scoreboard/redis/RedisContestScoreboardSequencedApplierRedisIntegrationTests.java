package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardSequencedApplier;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The poll script and the fence against a real Redis: the expected-watermark refusal, the atomic
 * {@code max}, submission-id idempotency, and scoring identical to the Stream script.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisContestScoreboardSequencedApplierRedisIntegrationTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 28, 9, 0);
    private static final long CONTEST = 7001L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisContestScoreboardSequencedApplier applier;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        applier = new RedisContestScoreboardSequencedApplier(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void issuesTheNextSequenceAndScoresTheResult() {
        assertThat(applier.apply(update(1, 1, 1, 10, SubmissionResult.WRONG_ANSWER), 0, 0)).isEqualTo(1L);
        assertThat(applier.apply(update(2, 1, 1, 20, SubmissionResult.ACCEPTED), 1, 0)).isEqualTo(2L);

        assertThat(applier.allocatorSequence()).isEqualTo(2L);
        assertThat(summary(1)).containsEntry("solved", "1")
                .containsEntry("penalty", Long.toString(20 + ContestScoreboardPolicy.PENALTY_PER_WRONG_MINUTES));
    }

    /**
     * The allocator is below the watermark the caller read: nothing is scored, nothing is marked
     * processed, no sequence is issued - the script returns before its first write.
     */
    @Test
    void anAllocatorBelowTheExpectedWatermarkChangesNothing() {
        applier.apply(update(1, 1, 1, 10, SubmissionResult.ACCEPTED), 0, 0);
        Set<String> keysBefore = redisTemplate.keys("contest:scoreboard:*");

        long outcome = applier.apply(update(2, 2, 1, 15, SubmissionResult.ACCEPTED), 5, 0);

        assertThat(outcome).isEqualTo(ContestScoreboardSequencedApplier.ROLLBACK);
        assertThat(redisTemplate.keys("contest:scoreboard:*")).isEqualTo(keysBefore);
        assertThat(applier.allocatorSequence()).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().isMember(ContestScoreboardRedisKeys.processed(CONTEST), "2")).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(ContestScoreboardRedisKeys.SUBMISSION_SEQUENCE, "2")).isFalse();
        assertThat(redisTemplate.opsForZSet().size(ContestScoreboardRedisKeys.ranking(CONTEST))).isEqualTo(1L);
    }

    /**
     * A restore that lands after the caller's own check and before the script: the script still sees the
     * restored allocator and refuses, so a sequence MySQL already holds is not issued a second time.
     */
    @Test
    void aRestoreBetweenTheCheckAndTheScriptIsRefusedInsideTheScript() {
        for (long id = 1; id <= 3; id++) {
            applier.apply(update(id, (int) id, 1, 10, SubmissionResult.ACCEPTED), id - 1, 0);
        }
        RedisScoreboardSnapshot snapshot = RedisScoreboardSnapshot.take(redisTemplate);   // allocator 3
        applier.apply(update(4, 4, 1, 10, SubmissionResult.ACCEPTED), 3, 0);
        applier.apply(update(5, 5, 1, 10, SubmissionResult.ACCEPTED), 4, 0);             // H = 5
        long watermarkTheCallerRead = 5;
        assertThat(applier.allocatorSequence()).isEqualTo(watermarkTheCallerRead);       // the caller's check passes

        snapshot.restoreInto(redisTemplate);
        long outcome = applier.apply(update(6, 6, 1, 10, SubmissionResult.ACCEPTED), watermarkTheCallerRead, 0);

        assertThat(outcome).isEqualTo(ContestScoreboardSequencedApplier.ROLLBACK);
        assertThat(applier.allocatorSequence()).as("sequence 4 was not issued again").isEqualTo(3L);
        assertThat(summary(6)).isEmpty();
    }

    @Test
    void theFenceRaisesTheAllocatorAndNeverLowersIt() {
        applier.apply(update(1, 1, 1, 10, SubmissionResult.ACCEPTED), 0, 0);

        assertThat(applier.fenceAllocator(9)).isEqualTo(9L);
        assertThat(applier.fenceAllocator(4)).isEqualTo(9L);
        assertThat(applier.allocatorSequence()).isEqualTo(9L);
        assertThat(applier.apply(update(2, 2, 1, 10, SubmissionResult.ACCEPTED), 9, 0))
                .as("after the fence a new result is issued a sequence above H")
                .isEqualTo(10L);
    }

    /**
     * Fences and applies racing from many threads: the allocator never ends below the highest fence and
     * no two results are ever issued the same sequence - both only hold if each script runs atomically.
     */
    @Test
    void fencesAndAppliesAreAtomicUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ConcurrentLinkedQueue<Long> issued = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            int base = worker * 1000;
            futures.add(pool.submit(() -> {
                for (int index = 1; index <= 150; index++) {
                    if (index % 10 == 0) {
                        applier.fenceAllocator(base / 10 + index);
                    } else {
                        issued.add(applier.apply(update(base + index, index % 20 + 1, index % 3 + 1, index,
                                SubmissionResult.ACCEPTED), 0, 0));
                    }
                }
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Set<Long> distinct = new HashSet<>(issued);
        assertThat(distinct).hasSameSizeAs(issued);
        assertThat(applier.allocatorSequence()).isGreaterThanOrEqualTo(Collections.max(issued));
        assertThat(applier.allocatorSequence()).isGreaterThanOrEqualTo(700 / 10 + 150);
    }

    /** A processed submission is not scored again and keeps its sequence: the poller's re-poll path. */
    @Test
    void aProcessedSubmissionKeepsItsSequenceAndIsNotScoredTwice() {
        long first = applier.apply(update(1, 1, 1, 10, SubmissionResult.WRONG_ANSWER), 0, 0);
        Map<Object, Object> summaryBefore = summary(1);

        long again = applier.apply(update(1, 1, 1, 10, SubmissionResult.WRONG_ANSWER), 1, 0);

        assertThat(again).isEqualTo(first);
        assertThat(applier.allocatorSequence()).isEqualTo(1L);
        assertThat(summary(1)).isEqualTo(summaryBefore);
    }

    /**
     * Range recovery passes the range's upper bound as the floor: a processed submission whose sequence
     * is inside the range is issued a fresh one, still without being scored again, so it leaves the range.
     */
    @Test
    void aProcessedSubmissionAtOrBelowTheFloorIsResequencedWithoutRescoring() {
        applier.apply(update(1, 1, 1, 10, SubmissionResult.ACCEPTED), 0, 0);
        applier.fenceAllocator(8);
        Map<Object, Object> summaryBefore = summary(1);

        long resequenced = applier.apply(update(1, 1, 1, 10, SubmissionResult.ACCEPTED), 8, 8);

        assertThat(resequenced).isEqualTo(9L);
        assertThat(summary(1)).isEqualTo(summaryBefore);
        assertThat(redisTemplate.opsForHash().get(ContestScoreboardRedisKeys.SUBMISSION_SEQUENCE, "1")).isEqualTo("9");
    }

    @Test
    void aPendingResultIsRefused() {
        assertThatThrownBy(() -> applier.apply(update(1, 1, 1, 10, SubmissionResult.PENDING), 0, 0))
                .hasStackTraceContaining("judged results only");
        assertThat(applier.allocatorSequence()).isZero();
    }

    /**
     * The poll script scores through the same Lua functions as the Stream script. Applying the same
     * judgements through each, into two contests, leaves identical standings.
     */
    @Test
    void scoresExactlyAsTheStreamScriptDoes() {
        ContestScoreboardApplier stream = new RedisContestScoreboardApplier(
                redisTemplate, new RedisTemplateContestRedisKeyValueClient(redisTemplate),
                new RedisContestScoreboardApplyMetrics(new SimpleMeterRegistry()), () -> false);
        long streamContest = 7002L;
        SubmissionResult[] results = {SubmissionResult.WRONG_ANSWER, SubmissionResult.ACCEPTED,
                SubmissionResult.TIME_LIMIT, SubmissionResult.ACCEPTED, SubmissionResult.WRONG_ANSWER};
        long expected = 0;
        for (int index = 0; index < 40; index++) {
            int user = index % 4 + 1;
            int problem = index % 3 + 1;
            SubmissionResult result = results[index % results.length];
            int minute = 97 - index * 2;   // out of order on purpose
            expected = applier.apply(update(100 + index, user, problem, minute, result), expected, 0);
            stream.apply(ContestScoreboardApplier.ApplyRequest.rebuild(100 + index,
                    new ContestScoreboardUpdate(100L + index, streamContest, (long) problem, 2000L + user,
                            START, START.plusMinutes(minute), result, null)));
        }

        assertThat(redisTemplate.opsForZSet().rangeWithScores(ContestScoreboardRedisKeys.ranking(CONTEST), 0, -1))
                .isEqualTo(redisTemplate.opsForZSet().rangeWithScores(ContestScoreboardRedisKeys.ranking(streamContest), 0, -1));
        for (int user = 1; user <= 4; user++) {
            assertThat(summary(user)).isEqualTo(redisTemplate.opsForHash()
                    .entries(ContestScoreboardRedisKeys.summary(streamContest, 2000L + user)));
        }
    }

    private Map<Object, Object> summary(int user) {
        return redisTemplate.opsForHash().entries(ContestScoreboardRedisKeys.summary(CONTEST, 2000L + user));
    }

    private static ContestScoreboardUpdate update(long submissionId, int user, int problem, int minute,
                                                  SubmissionResult result) {
        return new ContestScoreboardUpdate(submissionId, CONTEST, (long) problem, 2000L + user,
                START, START.plusMinutes(minute), result, null);
    }
}
