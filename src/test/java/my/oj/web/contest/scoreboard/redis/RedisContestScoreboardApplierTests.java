package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisContestScoreboardApplierTests {

    /**
     * The batch stops at the first failure across chunk boundaries: a failed event in the second chunk
     * ends the batch, and the third chunk is never sent - which is what keeps a later offset from being
     * carried over the poison by a script that would otherwise run.
     */
    @Test
    void applyAllStopsAtFirstFailureSoNoLaterOffsetCanJumpPoison() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(entry(1L, 0L), entry(1L, 1L)))
                .thenReturn(List.of(entry(1L, 2L), List.of(3L, 2L, -1L, "user_script: poison event")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisContestScoreboardApplier applier = chunked(template, registry, 2);

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests(6), 7L);

        assertThat(results).hasSize(4);
        assertThat(results.subList(0, 3)).allMatch(ContestScoreboardApplier.ApplyResult::newlyApplied);
        assertThat(results.get(3).succeeded()).isFalse();
        assertThat(results.get(3).correlationId()).isEqualTo(3L);
        assertThat(results.get(3).errorMessage()).contains("poison");
        // Two scripts for two chunks reached; the third chunk was never sent.
        verify(template, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThat(registry.get("contest.scoreboard.redis.apply.calls").summary().totalAmount()).isEqualTo(2.0);
    }

    /**
     * The first chunk is checked against the caller's floor; the second against the checkpoint the
     * first one left, which this call wrote itself.
     */
    @Test
    void theCheckpointFloorIsCarriedFromChunkToChunk() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(entry(1L, 0L), entry(2L, 1L)))
                .thenReturn(List.of(entry(1L, 2L)));
        RedisContestScoreboardApplier applier = chunked(template, new SimpleMeterRegistry(), 2);

        applier.applyAll(requests(3), 7L);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(template, times(2)).execute(any(RedisScript.class), anyList(), arguments.capture());
        List<Object[]> calls = arguments.getAllValues();
        assertThat(calls.get(0)[0]).isEqualTo("2");
        assertThat(calls.get(0)[1]).isEqualTo("7");
        assertThat(calls.get(1)[0]).isEqualTo("1");
        assertThat(calls.get(1)[1]).isEqualTo("7");
    }

    @Test
    void aRollbackReplyEndsTheBatchWithoutSendingTheRest() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(List.of(4L, 900L, -1L)));
        RedisContestScoreboardApplier applier = chunked(template, new SimpleMeterRegistry(), 2);

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests(5), 1000L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.rolledBack()).isTrue();
            assertThat(result.appliedOffset()).isEqualTo(900L);
        });
        verify(template, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void resetDropsOnlyContestKeysAndPreservesGlobalOffsetRepairKeys() {
        InMemoryContestRedisKeyValueClient redisClient = new InMemoryContestRedisKeyValueClient();
        RedisContestScoreboardApplier applier =
                new RedisContestScoreboardApplier(mock(StringRedisTemplate.class), redisClient);
        redisClient.zAdd(ContestScoreboardRedisKeys.ranking(7L), 1.0, "1001");
        redisClient.hSet(ContestScoreboardRedisKeys.summary(7L, 1001L), "solved", "1");
        redisClient.hSet(ContestScoreboardRedisKeys.problem(7L, 1001L, 11L), "accepted", "1");
        redisClient.sAdd(ContestScoreboardRedisKeys.processed(7L), "5001");
        redisClient.zAdd(ContestScoreboardRedisKeys.ranking(8L), 1.0, "1001");
        redisClient.sAdd(ContestScoreboardRedisKeys.STREAM_DB_PENDING, "5001");

        applier.reset(7L);

        assertThat(redisClient.zCard(ContestScoreboardRedisKeys.ranking(7L))).isZero();
        assertThat(redisClient.hGetAll(ContestScoreboardRedisKeys.summary(7L, 1001L))).isEmpty();
        assertThat(redisClient.hGetAll(ContestScoreboardRedisKeys.problem(7L, 1001L, 11L))).isEmpty();
        assertThat(redisClient.sIsMember(ContestScoreboardRedisKeys.processed(7L), "5001")).isFalse();
        assertThat(redisClient.zCard(ContestScoreboardRedisKeys.ranking(8L))).isEqualTo(1L);
        assertThat(redisClient.sIsMember(ContestScoreboardRedisKeys.STREAM_DB_PENDING, "5001")).isTrue();
    }

    private static List<ContestScoreboardApplier.ApplyRequest> requests(int count) {
        List<ContestScoreboardApplier.ApplyRequest> requests = new ArrayList<>();
        for (long offset = 0L; offset < count; offset++) {
            requests.add(ContestScoreboardApplier.ApplyRequest.stream(offset, payload(1001L + offset)));
        }
        return requests;
    }

    private static List<Object> entry(long status, long offset) {
        return List.of(status, offset, -1L);
    }

    private static RedisContestScoreboardApplier chunked(StringRedisTemplate template,
                                                         SimpleMeterRegistry registry,
                                                         int chunkSize) {
        return new RedisContestScoreboardApplier(template, new InMemoryContestRedisKeyValueClient(),
                new RedisContestScoreboardApplyMetrics(registry), ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, chunkSize);
    }

    private static ContestScoreboardUpdate payload(long submissionId) {
        return new ContestScoreboardUpdate(
                submissionId, 10L, 20L, 30L,
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                SubmissionResult.ACCEPTED,
                LocalDateTime.of(2026, 3, 10, 12, 2)
        );
    }
}
