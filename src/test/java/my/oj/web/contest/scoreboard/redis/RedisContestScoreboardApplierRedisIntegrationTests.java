package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.CheckpointAdvance;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class RedisContestScoreboardApplierRedisIntegrationTests {

    private static final long CONTEST_ID = 9001L;
    private static final long USER_ID = 101L;
    private static final long PROBLEM_ID = 11L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisContestScoreboardApplier applier;
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
        applier = new RedisContestScoreboardApplier(
                redisTemplate,
                new RedisTemplateContestRedisKeyValueClient(redisTemplate),
                new RedisContestScoreboardApplyMetrics(registry)
        );
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void liveApplyAtomicallyUpdatesScoreboardOffsetAndDbCompletionRepairSet() {
        assertThat(applier.apply(stream(0L, 1001L, SubmissionResult.WRONG_ANSWER, 2))).isZero();
        assertThat(applier.apply(stream(1L, 1002L, SubmissionResult.ACCEPTED, 10))).isEqualTo(1L);

        assertThat(applier.currentStreamOffset()).isEqualTo(1L);
        assertThat(redisTemplate.<String, String>opsForHash().entries(problemKey()))
                .containsOnly(
                        entry("w:1001", "2"),
                        entry("a:min", "10"),
                        entry("a:sid", "1002"),
                        entry("c:solved", "1"),
                        entry("c:penalty", "15")
                );
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "penalty")).isEqualTo("15");
        assertThat(redisTemplate.opsForSet().members(processedKey()))
                .containsExactlyInAnyOrder("1001", "1002");
        assertThat(redisTemplate.opsForSet().members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY))
                .containsExactlyInAnyOrder("1001", "1002");
    }

    @Test
    void replayedOffsetAndSubmissionDoNotDoubleApplyButNewDuplicateOffsetAdvancesCheckpoint() {
        ContestScoreboardUpdate accepted = payload(1001L, PROBLEM_ID, SubmissionResult.ACCEPTED, 10);

        assertThat(applier.apply(ContestScoreboardApplier.ApplyRequest.stream(0L, accepted))).isZero();
        assertThat(applier.apply(ContestScoreboardApplier.ApplyRequest.stream(0L, accepted))).isZero();
        assertThat(applier.apply(ContestScoreboardApplier.ApplyRequest.stream(1L, accepted))).isEqualTo(1L);

        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().members(processedKey())).containsExactly("1001");
        assertThat(applier.currentStreamOffset()).isEqualTo(1L);
    }

    /**
     * Offsets are not consecutive integers, and the script must not require them to be. A stream
     * hands over the offsets that exist, so a step from {@code 0} to {@code 2} is an ordinary one and
     * the checkpoint moves to the offset that actually arrived - never to a computed successor.
     */
    @Test
    void sparseForwardOffsetIsAppliedBecauseOffsetsAreNotConsecutiveIntegers() {
        applier.apply(stream(0L, 1001L, SubmissionResult.WRONG_ANSWER, 2));

        assertThat(applier.apply(stream(2L, 1002L, SubmissionResult.ACCEPTED, 10))).isEqualTo(2L);

        assertThat(applier.currentStreamOffset()).isEqualTo(2L);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().members(processedKey()))
                .containsExactlyInAnyOrder("1001", "1002");
    }

    /**
     * The other half of the same contract: a resubscribe re-reads from the checkpoint inclusive, so
     * the first delivery is normally an offset the standings already hold. It anchors the position and
     * changes nothing - including for a submission that never arrived before.
     */
    @Test
    void anOffsetAtOrBelowTheCheckpointIsAbsorbedWithoutTouchingTheStandings() {
        applier.apply(stream(0L, 1001L, SubmissionResult.ACCEPTED, 10));
        applier.apply(stream(2L, 1002L, SubmissionResult.WRONG_ANSWER, 20));

        assertThat(applier.apply(stream(2L, 1003L, SubmissionResult.ACCEPTED, 5))).isEqualTo(2L);

        assertThat(applier.currentStreamOffset()).isEqualTo(2L);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().members(processedKey())).doesNotContain("1003");
    }

    @Test
    void rebuildDoesNotMoveGlobalOffsetAndExplicitRecoveryMayBridgeRetentionGap() {
        applier.apply(stream(0L, 1001L, SubmissionResult.WRONG_ANSWER, 2));
        applier.reset(CONTEST_ID);

        assertThat(applier.apply(ContestScoreboardApplier.ApplyRequest.rebuild(
                1002L, payload(1002L, PROBLEM_ID, SubmissionResult.ACCEPTED, 10)))).isZero();
        assertThat(applier.currentStreamOffset()).isZero();

        // An anchor: the caller verified that the range below this offset was rebuilt, which is the
        // only way a checkpoint may cross offsets the stream never handed over.
        assertThat(applier.apply(ContestScoreboardApplier.ApplyRequest.stream(
                10L,
                payload(1010L, PROBLEM_ID + 1, SubmissionResult.ACCEPTED, 20),
                CheckpointAdvance.ANCHOR
        ))).isEqualTo(10L);
        assertThat(applier.currentStreamOffset()).isEqualTo(10L);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("2");
    }

    @Test
    void contestResetPreservesGlobalOffsetAndDbCompletionRepairSet() {
        applier.apply(stream(0L, 1001L, SubmissionResult.ACCEPTED, 10));

        applier.reset(CONTEST_ID);

        assertThat(redisTemplate.opsForZSet().size(rankingKey())).isZero();
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
        assertThat(applier.currentStreamOffset()).isZero();
        assertThat(redisTemplate.opsForSet().members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY))
                .containsExactly("1001");
    }

    @Test
    void malformedScoreboardStateDoesNotAdvanceOffset() {
        redisTemplate.opsForHash().put(problemKey(), "c:penalty", "not-an-integer");

        assertThatThrownBy(() -> applier.apply(stream(
                0L, 2002L, SubmissionResult.ACCEPTED, 10)))
                .isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Invalid integer value for c:penalty");

        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
        assertThat(redisTemplate.opsForSet().size(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY)).isZero();
    }

    @Test
    void applyAllStopsBeforeLaterOffsetWhenOneEventIsPoison() {
        long invalidContestId = CONTEST_ID + 1;
        redisTemplate.opsForValue().set(
                ContestScoreboardRedisKeys.ranking(invalidContestId), "wrong-type");

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(List.of(
                stream(0L, CONTEST_ID, 4101L, 41L, SubmissionResult.ACCEPTED, 1),
                stream(1L, invalidContestId, 4102L, 42L, SubmissionResult.ACCEPTED, 2),
                stream(2L, CONTEST_ID, 4103L, 43L, SubmissionResult.ACCEPTED, 3)
        ));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).succeeded()).isTrue();
        assertThat(results.get(1).succeeded()).isFalse();
        assertThat(results.get(1).errorMessage()).contains("Unexpected Redis key type");
        assertThat(applier.currentStreamOffset()).isZero();
        assertThat(redisTemplate.opsForSet().members(processedKey())).containsExactly("4101");
        assertThat(registry.get("contest.scoreboard.redis.lua.errors")
                .tag("kind", "unexpected_key_type").counter().count()).isEqualTo(1.0);
    }

    @Test
    void lateEarlierAttemptsKeepCommutativeScoreboardRule() {
        applier.apply(stream(0L, 1201L, SubmissionResult.WRONG_ANSWER, 4));
        applier.apply(stream(1L, 1203L, SubmissionResult.ACCEPTED, 20));
        applier.apply(stream(2L, 1202L, SubmissionResult.ACCEPTED, 9));

        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "penalty")).isEqualTo("14");
        assertThat(redisTemplate.opsForHash().get(problemKey(), "a:sid")).isEqualTo("1202");
    }

    private ContestScoreboardApplier.ApplyRequest stream(
            long offset, long submissionId, SubmissionResult result, int submittedMinute) {
        return ContestScoreboardApplier.ApplyRequest.stream(
                offset, payload(submissionId, PROBLEM_ID, result, submittedMinute));
    }

    private ContestScoreboardApplier.ApplyRequest stream(
            long offset,
            long contestId,
            long submissionId,
            long problemId,
            SubmissionResult result,
            int submittedMinute) {
        return ContestScoreboardApplier.ApplyRequest.stream(
                offset, payload(contestId, submissionId, problemId, result, submittedMinute));
    }

    private ContestScoreboardUpdate payload(
            long submissionId, long problemId, SubmissionResult result, int submittedMinute) {
        return payload(CONTEST_ID, submissionId, problemId, result, submittedMinute);
    }

    private ContestScoreboardUpdate payload(
            long contestId,
            long submissionId,
            long problemId,
            SubmissionResult result,
            int submittedMinute) {
        return new ContestScoreboardUpdate(
                submissionId,
                contestId,
                problemId,
                USER_ID,
                LocalDateTime.of(2026, 3, 10, 10, 0),
                LocalDateTime.of(2026, 3, 10, 10, submittedMinute),
                result,
                LocalDateTime.of(2026, 3, 10, 10, submittedMinute, 1)
        );
    }

    private void flushDatabase() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private String rankingKey() {
        return ContestScoreboardRedisKeys.ranking(CONTEST_ID);
    }

    private String summaryKey() {
        return ContestScoreboardRedisKeys.summary(CONTEST_ID, USER_ID);
    }

    private String problemKey() {
        return ContestScoreboardRedisKeys.problem(CONTEST_ID, USER_ID, PROBLEM_ID);
    }

    private String processedKey() {
        return ContestScoreboardRedisKeys.processed(CONTEST_ID);
    }
}
