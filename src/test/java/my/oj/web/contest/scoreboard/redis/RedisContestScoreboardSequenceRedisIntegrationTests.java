package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sequence half of the Lua contract, against a real Redis.
 *
 * <p>Sequences are issued inside the same invocation that mutates the standings, which is the whole
 * reason the detection paths can trust them, so the assertions here are about what the script
 * actually wrote - not about what a stub was asked to write.
 */
@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class RedisContestScoreboardSequenceRedisIntegrationTests {

    private static final long CONTEST_ID = 9101L;
    private static final long USER_ID = 201L;
    private static final long PROBLEM_ID = 21L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        int port = Integer.getInteger("redisPort", 16379);
        connectionFactory = new LettuceConnectionFactory("localhost", port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        flushDatabase();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void trackingIssuesEachNewResultTheNextGlobalSequence() {
        ContestScoreboardApplier applier = applier(true);

        applier.apply(stream(0L, 1001L));
        applier.apply(stream(1L, 1002L));

        assertThat(allocator()).isEqualTo(2L);
        assertThat(sequences(List.of(1001L, 1002L)))
                .containsExactly(Map.entry(1001L, 1L), Map.entry(1002L, 2L));
    }

    /**
     * The point of the flag: a mode that does not checkpoint by sequence must not pay for one, and
     * must not leave state behind that later looks like a checkpoint.
     */
    @Test
    void withoutTrackingNeitherTheAllocatorNorTheMappingIsWritten() {
        ContestScoreboardApplier applier = applier(false);

        applier.apply(stream(0L, 1001L));
        applier.apply(stream(1L, 1002L));

        assertThat(redisTemplate.hasKey(RedisContestScoreboardApplier.SEQUENCE_KEY)).isFalse();
        assertThat(redisTemplate.hasKey(RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY)).isFalse();
        // The scoreboard itself still advanced, so the flag changed nothing else.
        assertThat(applier.currentStreamOffset()).isEqualTo(1L);
    }

    /**
     * A duplicate delivery is not a new application, so it must not consume a sequence - otherwise
     * the allocator would climb on redelivery and every redelivered result would look like a lost
     * tail.
     */
    @Test
    void aDuplicateDeliveryDoesNotConsumeASequence() {
        ContestScoreboardApplier applier = applier(true);
        applier.apply(stream(0L, 1001L));

        applier.apply(stream(1L, 1001L));

        assertThat(allocator()).isEqualTo(1L);
        assertThat(sequences(List.of(1001L))).containsExactly(Map.entry(1001L, 1L));
    }

    /**
     * A mapping at or above the allocator is an inconsistent pair. Stepping over it is what keeps
     * the sequence strictly increasing, so a lost-tail check terminates instead of re-issuing a
     * sequence an existing mapping already holds.
     */
    @Test
    void aMappingAtOrAboveTheAllocatorIsSteppedOver() {
        ContestScoreboardApplier applier = applier(true);
        ContestScoreboardSequenceSource source = new RedisContestScoreboardSequenceSource(redisTemplate);
        redisTemplate.opsForHash().put(
                RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY, "1001", "40");
        redisTemplate.opsForValue().set(RedisContestScoreboardApplier.SEQUENCE_KEY, "7");

        applier.apply(stream(0L, 1001L));

        // 8 would be the next allocator value and 40 belongs to this submission already, so the
        // sequence has to clear both.
        assertThat(source.appliedSequences(List.of(1001L))).containsExactly(Map.entry(1001L, 41L));
        assertThat(allocator()).isEqualTo(41L);
    }

    /**
     * An unjudged result contributes nothing to the standings, so it is not sequenced either. That
     * matters beyond tidiness: the replay paths refuse to replay a PENDING row, so sequencing one
     * would leave a lost-tail candidate that could never be cleared.
     */
    @Test
    void anUnjudgedResultIsNotSequenced() {
        ContestScoreboardApplier applier = applier(true);

        applier.apply(ContestScoreboardApplier.ApplyRequest.rebuild(
                1001L, payload(1001L, SubmissionResult.PENDING)));

        assertThat(redisTemplate.hasKey(RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY)).isFalse();
        assertThat(redisTemplate.hasKey(RedisContestScoreboardApplier.SEQUENCE_KEY)).isFalse();
    }

    /**
     * A rebuild request carries no stream offset, but it is still an application and is sequenced:
     * the replay paths persist the sequence the scoreboard issued for the row they re-applied.
     */
    @Test
    void aRebuildRequestIsSequencedWithoutMovingTheCheckpoint() {
        ContestScoreboardApplier applier = applier(true);

        applier.apply(ContestScoreboardApplier.ApplyRequest.rebuild(
                1001L, payload(1001L, SubmissionResult.ACCEPTED)));

        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);
        assertThat(sequences(List.of(1001L))).containsExactly(Map.entry(1001L, 1L));
    }

    @Test
    void aMalformedAllocatorStopsTheApplyWithoutMutatingTheScoreboard() {
        ContestScoreboardApplier applier = applier(true);

        redisTemplate.opsForValue().set(RedisContestScoreboardApplier.SEQUENCE_KEY, "-5");

        assertThatThrownBy(() -> applier.apply(stream(0L, 1001L)))
                .isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Invalid negative scoreboard allocator sequence");

        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
        assertThat(registry.get("contest.scoreboard.redis.lua.errors")
                .tag("kind", "negative_allocator_sequence").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aMalformedMappingStopsTheApplyWithoutMutatingTheScoreboard() {
        ContestScoreboardApplier applier = applier(true);

        redisTemplate.opsForHash().put(
                RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY, "1001", "not-a-sequence");

        assertThatThrownBy(() -> applier.apply(stream(0L, 1001L)))
                .isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Invalid scoreboard submission sequence");

        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
        assertThat(registry.get("contest.scoreboard.redis.lua.errors")
                .tag("kind", "invalid_submission_sequence").counter().count()).isEqualTo(1.0);
    }

    /**
     * The one case the Java side cannot produce, so it is driven straight at the script: an
     * unrecognised tracking flag must fail rather than be read as "off", because a mode that
     * silently stopped sequencing would look exactly like a mode that never needed to.
     */
    @Test
    void anUnrecognisedTrackingFlagIsRejected() {
        assertThatThrownBy(() -> redisTemplate.execute(
                ContestScoreboardRedisScript.APPLY,
                keys(),
                (Object[]) arguments(1001L, "yes")))
                .isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Invalid scoreboard sequence tracking flag");
    }

    private ContestScoreboardApplier applier(boolean trackSequence) {
        return new RedisContestScoreboardApplier(
                redisTemplate,
                new RedisTemplateContestRedisKeyValueClient(redisTemplate),
                new RedisContestScoreboardApplyMetrics(registry),
                () -> trackSequence
        );
    }

    private Map<Long, Long> sequences(List<Long> submissionIds) {
        return new RedisContestScoreboardSequenceSource(redisTemplate).appliedSequences(submissionIds);
    }

    private long allocator() {
        String value = redisTemplate.opsForValue().get(RedisContestScoreboardApplier.SEQUENCE_KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    private ContestScoreboardApplier.ApplyRequest stream(long offset, long submissionId) {
        return ContestScoreboardApplier.ApplyRequest.stream(
                offset, payload(submissionId, SubmissionResult.ACCEPTED));
    }

    private ContestScoreboardUpdate payload(long submissionId, SubmissionResult result) {
        return new ContestScoreboardUpdate(
                submissionId,
                CONTEST_ID,
                PROBLEM_ID,
                USER_ID,
                LocalDateTime.of(2026, 3, 10, 10, 0),
                LocalDateTime.of(2026, 3, 10, 10, 5),
                result,
                null
        );
    }

    private static List<String> keys() {
        return List.of(
                RedisContestScoreboardApplier.STREAM_OFFSET_KEY,
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY,
                ContestScoreboardRedisKeys.ranking(CONTEST_ID),
                ContestScoreboardRedisKeys.summary(CONTEST_ID, USER_ID),
                ContestScoreboardRedisKeys.problem(CONTEST_ID, USER_ID, PROBLEM_ID),
                ContestScoreboardRedisKeys.processed(CONTEST_ID),
                RedisContestScoreboardApplier.SEQUENCE_KEY,
                RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY
        );
    }

    private static String[] arguments(long submissionId, String trackingFlag) {
        return new String[]{
                "0",
                "0",
                Long.toString(submissionId),
                SubmissionResult.ACCEPTED.name(),
                "5",
                "20",
                "100",
                "1",
                Long.toString(USER_ID),
                trackingFlag
        };
    }

    private String processedKey() {
        return ContestScoreboardRedisKeys.processed(CONTEST_ID);
    }

    private void flushDatabase() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
