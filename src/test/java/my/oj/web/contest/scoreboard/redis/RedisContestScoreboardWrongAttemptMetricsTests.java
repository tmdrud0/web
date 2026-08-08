package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisContestScoreboardWrongAttemptMetricsTests {

    @Test
    void pollCachesExactWrongFieldCountForScrapes() {
        ContestRedisKeyValueClient redisClient = mock(ContestRedisKeyValueClient.class);
        Set<String> keys = Set.of("contest:scoreboard:1:user:2:problem:3");
        given(redisClient.scan(ContestScoreboardRedisKeys.problemPattern())).willReturn(keys);
        given(redisClient.countHashFieldsWithPrefix(anyCollection(), eq("w:"))).willReturn(17L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisContestScoreboardWrongAttemptMetrics metrics =
                new RedisContestScoreboardWrongAttemptMetrics(redisClient);
        metrics.bindTo(registry);

        metrics.poll();

        assertThat(registry.get("contest.scoreboard.redis.wrong.attempt").gauge().value())
                .isEqualTo(17.0);
    }

    @Test
    void failedPollRetainsLastValueAndIncrementsFailureCounter() {
        ContestRedisKeyValueClient redisClient = mock(ContestRedisKeyValueClient.class);
        Set<String> keys = Set.of("contest:scoreboard:1:user:2:problem:3");
        given(redisClient.scan(ContestScoreboardRedisKeys.problemPattern()))
                .willReturn(keys)
                .willThrow(new IllegalStateException("redis unavailable"));
        given(redisClient.countHashFieldsWithPrefix(anyCollection(), eq("w:"))).willReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisContestScoreboardWrongAttemptMetrics metrics =
                new RedisContestScoreboardWrongAttemptMetrics(redisClient);
        metrics.bindTo(registry);

        metrics.poll();
        metrics.poll();

        assertThat(metrics.currentWrongAttemptFields()).isEqualTo(5L);
        assertThat(registry.get("contest.scoreboard.redis.wrong.attempt.poll.failures")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("contest.scoreboard.redis.wrong.attempt.poll")
                .timer().count()).isEqualTo(2L);
    }

    @Test
    void largeKeyspaceUsesBoundedSampleAndScalesTheEstimate() {
        ContestRedisKeyValueClient redisClient = mock(ContestRedisKeyValueClient.class);
        Set<String> keys = IntStream.range(0, 2_000)
                .mapToObj(index -> "contest:scoreboard:1:user:" + index + ":problem:1")
                .collect(Collectors.toSet());
        given(redisClient.scan(ContestScoreboardRedisKeys.problemPattern())).willReturn(keys);
        given(redisClient.countHashFieldsWithPrefix(anyCollection(), eq("w:"))).willReturn(1_500L);
        RedisContestScoreboardWrongAttemptMetrics metrics =
                new RedisContestScoreboardWrongAttemptMetrics(redisClient);

        metrics.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> sample = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(redisClient).countHashFieldsWithPrefix(sample.capture(), eq("w:"));
        assertThat(sample.getValue()).hasSize(
                RedisContestScoreboardWrongAttemptMetrics.MAX_SAMPLED_PROBLEM_HASHES);
        assertThat(metrics.currentWrongAttemptFields()).isEqualTo(3_000L);
    }
}
