package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardAppliedAtCompletionTests {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private JdbcContestScoreboardAppliedAtWriter writer;

    @Test
    void removesRepairIdsOnlyAfterMysqlBatchSucceeds() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        ContestScoreboardAppliedAtCompletion completion = completion(2);

        completion.complete(List.of(3L, 1L, 3L));

        InOrder order = inOrder(writer, setOperations);
        order.verify(writer).markApplied(List.of(3L, 1L));
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

        InOrder order = inOrder(writer, setOperations);
        order.verify(writer).markApplied(List.of(1L, 2L));
        order.verify(setOperations).remove(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY, "1", "2");
        order.verify(writer).markApplied(List.of(3L));
        order.verify(setOperations).remove(
                RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY, "3");
        verify(setOperations).members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY);
    }

    private ContestScoreboardAppliedAtCompletion completion(int batchSize) {
        return new ContestScoreboardAppliedAtCompletion(
                redisTemplate,
                writer,
                new ContestScoreboardStreamConsumerProperties(
                        batchSize, batchSize, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096)
        );
    }
}
