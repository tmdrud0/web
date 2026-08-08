package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisContestScoreboardApplierTests {

    @Test
    void applyAllStopsAtFirstFailureSoNoLaterOffsetCanJumpPoison() {
        CountingApplier applier = new CountingApplier(1L);

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).succeeded()).isTrue();
        assertThat(results.get(0).appliedOffset()).isZero();
        assertThat(results.get(1).succeeded()).isFalse();
        assertThat(results.get(1).errorMessage()).contains("poison");
        assertThat(applier.calls).containsExactly(0L, 1L);
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

    private static List<ContestScoreboardApplier.ApplyRequest> requests() {
        return List.of(
                ContestScoreboardApplier.ApplyRequest.stream(0L, payload(1001L)),
                ContestScoreboardApplier.ApplyRequest.stream(1L, payload(1002L)),
                ContestScoreboardApplier.ApplyRequest.stream(2L, payload(1003L))
        );
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

    private static final class CountingApplier extends RedisContestScoreboardApplier {
        private final long failingOffset;
        private final java.util.ArrayList<Long> calls = new java.util.ArrayList<>();

        private CountingApplier(long failingOffset) {
            super(mock(StringRedisTemplate.class), new InMemoryContestRedisKeyValueClient(),
                    new RedisContestScoreboardApplyMetrics(new SimpleMeterRegistry()));
            this.failingOffset = failingOffset;
        }

        @Override
        public Long apply(ApplyRequest request) {
            calls.add(request.streamOffset());
            if (request.streamOffset() == failingOffset) {
                throw new IllegalStateException("poison event");
            }
            return request.streamOffset();
        }
    }
}
