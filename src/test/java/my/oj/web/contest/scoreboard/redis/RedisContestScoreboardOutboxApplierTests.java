package my.oj.web.contest.scoreboard.redis;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxApplier;
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

class RedisContestScoreboardOutboxApplierTests {

    @Test
    void connectionFailureFailsTheBatchWithoutStartingPerEventFallback() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<String>>any()))
                .willReturn("script-sha");
        given(redisTemplate.executePipelined(
                org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()
        )).willThrow(new RedisConnectionFailureException("redis unavailable"));
        CountingApplier applier = new CountingApplier(redisTemplate);

        List<ContestScoreboardOutboxApplier.ApplyResult> results = applier.applyAll(requests());

        assertThat(results).hasSize(2).allMatch(result -> !result.succeeded());
        assertThat(results).extracting(ContestScoreboardOutboxApplier.ApplyResult::errorMessage)
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

        List<ContestScoreboardOutboxApplier.ApplyResult> results = applier.applyAll(requests());

        assertThat(results).hasSize(2).allMatch(ContestScoreboardOutboxApplier.ApplyResult::succeeded);
        assertThat(results).extracting(ContestScoreboardOutboxApplier.ApplyResult::redisSequence)
                .containsExactly(1L, 2L);
        assertThat(applier.individualCalls).isEqualTo(2);
    }

    private List<ContestScoreboardOutboxApplier.ApplyRequest> requests() {
        return List.of(
                new ContestScoreboardOutboxApplier.ApplyRequest(1L, payload(1001L)),
                new ContestScoreboardOutboxApplier.ApplyRequest(2L, payload(1002L))
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

    private static class CountingApplier extends RedisContestScoreboardOutboxApplier {

        private int individualCalls;

        private CountingApplier(StringRedisTemplate redisTemplate) {
            super(redisTemplate);
        }

        @Override
        public Long apply(Long eventId, ContestScoreboardUpdate update) {
            individualCalls++;
            return eventId;
        }
    }
}
