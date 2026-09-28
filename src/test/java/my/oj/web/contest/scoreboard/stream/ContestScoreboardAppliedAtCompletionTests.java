package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardAppliedAtCompletionTests {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private ContestScoreboardAppliedMarker appliedMarker;

    @Test
    void removesRepairIdsOnlyAfterMysqlBatchSucceeds() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        ContestScoreboardAppliedAtCompletion completion = completion(2);

        completion.complete(List.of(3L, 1L, 3L));

        InOrder order = inOrder(appliedMarker, setOperations);
        order.verify(appliedMarker).markApplied(List.of(3L, 1L));
        order.verify(setOperations).remove(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY, "3", "1");
    }

    @Test
    void startupRepairUsesConfiguredJdbcBatchSize() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY))
                .thenReturn(Set.of("3", "1", "2"));
        ContestScoreboardAppliedAtCompletion completion = completion(2);

        completion.repairPending();

        InOrder order = inOrder(appliedMarker, setOperations);
        order.verify(appliedMarker).markApplied(List.of(1L, 2L));
        order.verify(setOperations).remove(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY, "1", "2");
        order.verify(appliedMarker).markApplied(List.of(3L));
        order.verify(setOperations).remove(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY, "3");
        verify(setOperations).members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY);
    }

    /** stream-offset with tracking off: no MySQL UPDATE, no SREM, and no repair of a leftover set. */
    @Test
    void withTrackingOffNothingIsWrittenOrRepaired() {
        ContestScoreboardAppliedAtCompletion completion = new ContestScoreboardAppliedAtCompletion(
                redisTemplate, appliedMarker, properties(2), ContestScoreboardAppliedAtTracking.DISABLED);

        completion.complete(List.of(3L, 1L));
        completion.repairPending();

        verifyNoInteractions(appliedMarker, redisTemplate);
    }

    /**
     * The ACK waits for nothing but the Lua call: with tracking off the processor returns - and the
     * listener lets the container acknowledge - without a MySQL write or a Redis set operation, even
     * with a db-pending set left over from before the switch.
     */
    @Test
    void withTrackingOffABatchIsAnsweredWithoutWaitingForMySql() {
        ContestScoreboardApplier applier = mock(ContestScoreboardApplier.class);
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 5L);
        when(applier.applyAll(anyList(), anyLong())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), request.streamOffset()))
                    .toList();
        });
        ContestScoreboardRecoveryStrategy strategy = mock(ContestScoreboardRecoveryStrategy.class);
        ContestScoreboardAppliedAtCompletion completion = new ContestScoreboardAppliedAtCompletion(
                redisTemplate, appliedMarker, properties(500), ContestScoreboardAppliedAtTracking.DISABLED);
        ContestScoreboardStreamProcessor processor = new ContestScoreboardStreamProcessor(applier, completion,
                new ContestScoreboardStreamPosition(), strategy,
                new ContestScoreboardStreamMetrics(new SimpleMeterRegistry()), new ContestScoreboardApplyLock());
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);

        long applied = processor.process(List.of(
                new ContestScoreboardStreamEvent(4L, message(104L, now)),
                new ContestScoreboardStreamEvent(5L, message(105L, now))));

        assertThat(applied).isEqualTo(5L);
        verifyNoInteractions(appliedMarker, redisTemplate);
    }

    private static ContestJudgeResultStreamMessage message(long submissionId, LocalDateTime now) {
        return new ContestJudgeResultStreamMessage(ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                submissionId, 10L, 20L, 30L, now.minusHours(1), now.minusMinutes(1), now, SubmissionResult.ACCEPTED);
    }

    private static ContestScoreboardStreamConsumerProperties properties(int batchSize) {
        return new ContestScoreboardStreamConsumerProperties(
                batchSize, batchSize, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096);
    }

    private ContestScoreboardAppliedAtCompletion completion(int batchSize) {
        return new ContestScoreboardAppliedAtCompletion(
                redisTemplate,
                appliedMarker,
                new ContestScoreboardStreamConsumerProperties(
                        batchSize, batchSize, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096)
        );
    }
}
