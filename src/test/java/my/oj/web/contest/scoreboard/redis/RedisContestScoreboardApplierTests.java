package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import io.lettuce.core.RedisCommandExecutionException;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class RedisContestScoreboardApplierTests {

    @Test
    void connectionFailureFailsTheBatchWithoutStartingPerEventFallback() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<String>>any()))
                .willReturn("script-sha");
        given(redisTemplate.executePipelined(
                org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()
        )).willThrow(new RedisConnectionFailureException("redis unavailable"));
        CountingApplier applier = new CountingApplier(redisTemplate);

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests());

        assertThat(results).hasSize(2).allMatch(result -> !result.succeeded());
        assertThat(results).extracting(ContestScoreboardApplier.ApplyResult::errorMessage)
                .allMatch(message -> message.contains("redis unavailable"));
        assertThat(applier.individualCalls).isZero();
    }

    @Test
    void scriptCommandFailureUsesIdempotentPerEventFallbackToClassifyResults() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<String>>any()))
                .willReturn("script-sha");
        given(redisTemplate.executePipelined(
                org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()
        )).willThrow(new RedisCommandExecutionException("ERR invalid key type"));
        CountingApplier applier = new CountingApplier(redisTemplate);

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests());

        assertThat(results).hasSize(2).allMatch(ContestScoreboardApplier.ApplyResult::succeeded);
        assertThat(results).extracting(ContestScoreboardApplier.ApplyResult::sequence)
                .containsExactly(1L, 2L);
        assertThat(applier.individualCalls).isEqualTo(2);
    }

    @Test
    void resetDropsOnlyTheContestsOwnKeys() {
        InMemoryContestRedisKeyValueClient redisClient = new InMemoryContestRedisKeyValueClient();
        RedisContestScoreboardApplier applier =
                new RedisContestScoreboardApplier(mock(StringRedisTemplate.class), redisClient);
        redisClient.zAdd(ContestScoreboardRedisKeys.ranking(7L), 1.0, "1001");
        redisClient.hSet(ContestScoreboardRedisKeys.summary(7L, 1001L), "solved", "1");
        redisClient.hSet(ContestScoreboardRedisKeys.problem(7L, 1001L, 11L), "accepted", "1");
        redisClient.sAdd(ContestScoreboardRedisKeys.processed(7L), "5001");
        redisClient.zAdd(ContestScoreboardRedisKeys.ranking(8L), 1.0, "1001");
        redisClient.hSet(ContestScoreboardRedisKeys.OUTBOX_SUBMISSION_SEQUENCE, "5001", "1");

        applier.reset(7L);

        assertThat(redisClient.zCard(ContestScoreboardRedisKeys.ranking(7L))).isZero();
        assertThat(redisClient.hGetAll(ContestScoreboardRedisKeys.summary(7L, 1001L))).isEmpty();
        assertThat(redisClient.hGetAll(ContestScoreboardRedisKeys.problem(7L, 1001L, 11L))).isEmpty();
        assertThat(redisClient.sIsMember(ContestScoreboardRedisKeys.processed(7L), "5001")).isFalse();
        assertThat(redisClient.zCard(ContestScoreboardRedisKeys.ranking(8L))).isEqualTo(1L);
        assertThat(redisClient.hGet(ContestScoreboardRedisKeys.OUTBOX_SUBMISSION_SEQUENCE, "5001"))
                .isEqualTo("1");
    }

    private List<ContestScoreboardApplier.ApplyRequest> requests() {
        return List.of(
                new ContestScoreboardApplier.ApplyRequest(1L, payload(1001L)),
                new ContestScoreboardApplier.ApplyRequest(2L, payload(1002L))
        );
    }

    private ContestScoreboardUpdate payload(long submissionId) {
        return new ContestScoreboardUpdate(
                submissionId,
                10L,
                20L,
                30L,
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                SubmissionResult.ACCEPTED,
                LocalDateTime.of(2026, 3, 10, 12, 2)
        );
    }

    private static class CountingApplier extends RedisContestScoreboardApplier {

        private int individualCalls;

        private CountingApplier(StringRedisTemplate redisTemplate) {
            super(redisTemplate, new InMemoryContestRedisKeyValueClient());
        }

        @Override
        public Long apply(Long eventId, ContestScoreboardUpdate update) {
            individualCalls++;
            return eventId;
        }
    }
}
