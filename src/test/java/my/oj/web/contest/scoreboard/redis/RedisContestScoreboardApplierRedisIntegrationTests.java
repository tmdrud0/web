package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
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

@EnabledIfSystemProperty(named = "redisIntegration", matches = "true")
class RedisContestScoreboardApplierRedisIntegrationTests {

    private static final long CONTEST_ID = 9001L;
    private static final long USER_ID = 101L;
    private static final long PROBLEM_ID = 11L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisContestScoreboardApplier applier;

    @BeforeEach
    void setUp() {
        int port = Integer.getInteger("redisPort", 16379);
        connectionFactory = new LettuceConnectionFactory("localhost", port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        flushDatabase();
        applier = new RedisContestScoreboardApplier(redisTemplate, new RedisTemplateContestRedisKeyValueClient(redisTemplate));
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void applyAtomicallyAllocatesSequenceUpdatesScoreboardAndDeduplicatesEvent() {
        Long wrongSequence = applier.apply(501L, payload(1001L, SubmissionResult.WRONG_ANSWER, 2));
        Long acceptedSequence = applier.apply(502L, payload(1002L, SubmissionResult.ACCEPTED, 10));
        Long repeatedSequence = applier.apply(502L, payload(1002L, SubmissionResult.ACCEPTED, 10));
        redisTemplate.delete(RedisContestScoreboardApplier.SEQUENCE_KEY);
        Long restoredSequence = applier.apply(502L, payload(1002L, SubmissionResult.ACCEPTED, 10));

        assertThat(wrongSequence).isEqualTo(1L);
        assertThat(acceptedSequence).isEqualTo(2L);
        assertThat(repeatedSequence).isEqualTo(2L);
        assertThat(restoredSequence).isEqualTo(2L);
        assertThat(applier.currentSequence()).isEqualTo(2L);
        assertThat(redisTemplate.opsForHash().get(problemKey(), "wrongAttempts")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(problemKey(), "accepted")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "penalty")).isEqualTo("15");
        assertThat(redisTemplate.opsForSet().members(processedKey()))
                .containsExactlyInAnyOrder("1001", "1002");
        assertThat(redisTemplate.opsForHash().get("contest:scoreboard:outbox:submission", "1001"))
                .isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get("contest:scoreboard:outbox:submission", "1002"))
                .isEqualTo("2");
    }

    /**
     * The outbox row id is only a correlation token. Replaying the same submission under a
     * different id — which is exactly what a rebuild does — must not apply it twice.
     */
    @Test
    void deduplicationKeysOffTheSubmissionRatherThanTheEventId() {
        Long first = applier.apply(501L, payload(1001L, SubmissionResult.ACCEPTED, 10));
        Long replayedUnderAnotherEventId = applier.apply(9501L, payload(1001L, SubmissionResult.ACCEPTED, 10));

        assertThat(replayedUnderAnotherEventId).isEqualTo(first);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().members(processedKey())).containsExactly("1001");
    }

    @Test
    void resetClearsStandingsWhileSequencesSurviveForTheRebuild() {
        applier.apply(501L, payload(1001L, SubmissionResult.ACCEPTED, 10));
        assertThat(redisTemplate.opsForZSet().size(rankingKey())).isEqualTo(1L);

        applier.reset(CONTEST_ID);

        assertThat(redisTemplate.opsForZSet().size(rankingKey())).isZero();
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
        assertThat(redisTemplate.opsForHash().size(summaryKey())).isZero();
        assertThat(applier.currentSequence()).isEqualTo(1L);

        Long rebuilt = applier.apply(9501L, payload(1001L, SubmissionResult.ACCEPTED, 10));

        assertThat(rebuilt).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
    }

    @Test
    void wrongKeyTypeIsRejectedBeforeAnySequenceOrScoreboardWrite() {
        redisTemplate.opsForValue().set(rankingKey(), "wrong-type");

        assertThatThrownBy(() -> applier.apply(
                601L,
                payload(2001L, SubmissionResult.ACCEPTED, 10)
        )).isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Unexpected Redis key type");

        assertThat(redisTemplate.opsForValue().get(RedisContestScoreboardApplier.SEQUENCE_KEY)).isNull();
        assertThat(redisTemplate.opsForHash().size("contest:scoreboard:outbox:submission")).isZero();
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
    }

    @Test
    void malformedScoreboardFieldIsRejectedBeforeSequenceAllocation() {
        redisTemplate.opsForHash().put(problemKey(), "wrongAttempts", "not-an-integer");

        assertThatThrownBy(() -> applier.apply(
                602L,
                payload(2002L, SubmissionResult.ACCEPTED, 10)
        )).isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Invalid integer value for wrongAttempts");

        assertThat(redisTemplate.opsForValue().get(RedisContestScoreboardApplier.SEQUENCE_KEY)).isNull();
        assertThat(redisTemplate.opsForHash().size("contest:scoreboard:outbox:submission")).isZero();
        assertThat(redisTemplate.opsForHash().size(summaryKey())).isZero();
        assertThat(redisTemplate.opsForSet().size(processedKey())).isZero();
    }

    @Test
    void requeuedCollisionGroupRecoversAfterSequenceReuse() {
        ContestScoreboardUpdate first = payload(3001L, 21L, SubmissionResult.ACCEPTED, 1);
        ContestScoreboardUpdate lost = payload(3002L, 22L, SubmissionResult.ACCEPTED, 2);
        ContestScoreboardUpdate afterRollback = payload(3003L, 23L, SubmissionResult.ACCEPTED, 3);

        assertThat(applier.apply(701L, first)).isEqualTo(1L);
        assertThat(applier.apply(702L, lost)).isEqualTo(2L);

        flushDatabase();
        assertThat(applier.apply(701L, first)).isEqualTo(1L);
        assertThat(applier.apply(703L, afterRollback)).isEqualTo(2L);

        Long recoveredSequence = applier.apply(702L, lost);
        Long unchangedSequence = applier.apply(703L, afterRollback);

        assertThat(recoveredSequence).isEqualTo(3L);
        assertThat(unchangedSequence).isEqualTo(2L);
        assertThat(applier.currentSequence()).isEqualTo(3L);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("3");
        assertThat(redisTemplate.opsForSet().members(processedKey()))
                .containsExactlyInAnyOrder("3001", "3002", "3003");
    }

    @Test
    void applyAllReturnsPipelinedResultsInRequestOrder() {
        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(List.of(
                new ContestScoreboardApplier.ApplyRequest(
                        801L,
                        payload(4001L, 31L, SubmissionResult.WRONG_ANSWER, 1)
                ),
                new ContestScoreboardApplier.ApplyRequest(
                        802L,
                        payload(4002L, 32L, SubmissionResult.ACCEPTED, 2)
                ),
                new ContestScoreboardApplier.ApplyRequest(
                        803L,
                        payload(4003L, 33L, SubmissionResult.ACCEPTED, 3)
                )
        ));

        assertThat(results).extracting(ContestScoreboardApplier.ApplyResult::eventId)
                .containsExactly(801L, 802L, 803L);
        assertThat(results).allMatch(ContestScoreboardApplier.ApplyResult::succeeded);
        assertThat(results).extracting(ContestScoreboardApplier.ApplyResult::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(applier.currentSequence()).isEqualTo(3L);
        assertThat(redisTemplate.opsForSet().members(processedKey()))
                .containsExactlyInAnyOrder("4001", "4002", "4003");
    }

    @Test
    void applyAllKeepsSuccessfulResultsAroundAnIndividualScriptFailure() {
        long invalidContestId = CONTEST_ID + 1;
        redisTemplate.opsForValue().set(
                "contest:scoreboard:" + invalidContestId + ":ranking",
                "wrong-type"
        );

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(List.of(
                new ContestScoreboardApplier.ApplyRequest(
                        811L,
                        payload(CONTEST_ID, 4101L, 41L, SubmissionResult.ACCEPTED, 1)
                ),
                new ContestScoreboardApplier.ApplyRequest(
                        812L,
                        payload(invalidContestId, 4102L, 42L, SubmissionResult.ACCEPTED, 2)
                ),
                new ContestScoreboardApplier.ApplyRequest(
                        813L,
                        payload(CONTEST_ID, 4103L, 43L, SubmissionResult.ACCEPTED, 3)
                )
        ));

        assertThat(results.get(0).succeeded()).as("pipeline results: %s", results).isTrue();
        assertThat(results.get(0).sequence()).isEqualTo(1L);
        assertThat(results.get(1).succeeded()).isFalse();
        assertThat(results.get(1).errorMessage()).contains("Unexpected Redis key type");
        assertThat(results.get(2).succeeded()).isTrue();
        assertThat(results.get(2).sequence()).isEqualTo(2L);
        assertThat(applier.currentSequence()).isEqualTo(2L);
        assertThat(redisTemplate.opsForSet().members(processedKey()))
                .containsExactlyInAnyOrder("4101", "4103");
        assertThat(redisTemplate.opsForSet().size(
                "contest:scoreboard:" + invalidContestId + ":processed"
        )).isZero();
    }

    private ContestScoreboardUpdate payload(Long submissionId,
                                                    SubmissionResult result,
                                                    int submittedMinute) {
        return payload(submissionId, PROBLEM_ID, result, submittedMinute);
    }

    private ContestScoreboardUpdate payload(Long submissionId,
                                                    Long problemId,
                                                    SubmissionResult result,
                                                    int submittedMinute) {
        return payload(CONTEST_ID, submissionId, problemId, result, submittedMinute);
    }

    private ContestScoreboardUpdate payload(Long contestId,
                                                    Long submissionId,
                                                    Long problemId,
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
        return "contest:scoreboard:" + CONTEST_ID + ":ranking";
    }

    private String summaryKey() {
        return "contest:scoreboard:" + CONTEST_ID + ":user:" + USER_ID + ":summary";
    }

    private String problemKey() {
        return "contest:scoreboard:" + CONTEST_ID + ":user:" + USER_ID + ":problem:" + PROBLEM_ID;
    }

    private String processedKey() {
        return "contest:scoreboard:" + CONTEST_ID + ":processed";
    }
}
