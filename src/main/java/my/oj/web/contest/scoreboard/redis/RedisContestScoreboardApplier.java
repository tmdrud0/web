package my.oj.web.contest.scoreboard.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RedisContestScoreboardApplier implements ContestScoreboardApplier {

    public static final String STREAM_OFFSET_KEY = ContestScoreboardRedisKeys.STREAM_OFFSET;
    public static final String STREAM_DB_PENDING_KEY = ContestScoreboardRedisKeys.STREAM_DB_PENDING;

    private final StringRedisTemplate redisTemplate;
    private final ContestRedisKeyValueClient redisClient;
    private final RedisContestScoreboardApplyMetrics metrics;

    public RedisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                         ContestRedisKeyValueClient redisClient) {
        this(redisTemplate, redisClient,
                new RedisContestScoreboardApplyMetrics(new CompositeMeterRegistry()));
    }

    public RedisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                         ContestRedisKeyValueClient redisClient,
                                         RedisContestScoreboardApplyMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.redisClient = redisClient;
        this.metrics = metrics;
        if (redisTemplate.getConnectionFactory() instanceof LettuceConnectionFactory connectionFactory) {
            connectionFactory.setPipeliningFlushPolicy(
                    LettuceConnection.PipeliningFlushPolicy.flushOnClose()
            );
        }
    }

    @Override
    public Long apply(ApplyRequest request) {
        validate(request);
        Long appliedOffset;
        try {
            appliedOffset = redisTemplate.execute(
                    ContestScoreboardRedisScript.APPLY,
                    keys(request.update()),
                    (Object[]) arguments(request)
            );
        } catch (RuntimeException failure) {
            if (hasCommandExecutionFailure(failure)) {
                metrics.recordLuaError(failure);
            }
            throw failure;
        }
        if (appliedOffset == null) {
            throw new IllegalStateException("Redis scoreboard script returned no stream offset");
        }
        return appliedOffset;
    }

    /**
     * Live stream batches are intentionally executed in offset order rather than pipelined.
     * Redis continues executing commands after one pipelined EVAL fails, which could advance a
     * later offset past poison. Fail-fast ordering is the recovery contract; MySQL completion is
     * still a JDBC batch so transport comparisons retain the same database write shape.
     */
    @Override
    public List<ApplyResult> applyAll(List<ApplyRequest> requests) {
        long startedNanos = System.nanoTime();
        try {
            return ContestScoreboardApplier.super.applyAll(requests);
        } finally {
            metrics.recordPipeline(Duration.ofNanos(System.nanoTime() - startedNanos));
        }
    }

    @Override
    public long currentStreamOffset() {
        String value = redisTemplate.opsForValue().get(STREAM_OFFSET_KEY);
        if (value == null || value.isBlank()) {
            return -1L;
        }
        return Long.parseLong(value);
    }

    /**
     * Drops one contest's standings and duplicate-work marker. The global stream offset and the
     * DB-completion repair set survive because neither is scoped to one contest.
     */
    @Override
    public void reset(long contestId) {
        Set<String> keys = new HashSet<>(redisClient.scan(ContestScoreboardRedisKeys.userPattern(contestId)));
        keys.add(ContestScoreboardRedisKeys.ranking(contestId));
        keys.add(ContestScoreboardRedisKeys.processed(contestId));
        redisClient.delete(keys);
    }

    private static boolean hasCommandExecutionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RedisCommandExecutionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void validate(ApplyRequest request) {
        ContestScoreboardUpdate update = request == null ? null : request.update();
        if (request == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            throw new IllegalArgumentException("Scoreboard event and update fields are required");
        }
    }

    private static List<String> keys(ContestScoreboardUpdate update) {
        return List.of(
                STREAM_OFFSET_KEY,
                STREAM_DB_PENDING_KEY,
                ContestScoreboardRedisKeys.ranking(update.contestId()),
                ContestScoreboardRedisKeys.summary(update.contestId(), update.userId()),
                ContestScoreboardRedisKeys.problem(update.contestId(), update.userId(), update.problemId()),
                ContestScoreboardRedisKeys.processed(update.contestId())
        );
    }

    private static String[] arguments(ApplyRequest request) {
        ContestScoreboardUpdate update = request.update();
        return new String[]{
                request.streamOffset() == null ? "" : Long.toString(request.streamOffset()),
                request.allowOffsetGap() ? "1" : "0",
                Long.toString(update.contestSubmissionId()),
                update.result().name(),
                Long.toString(ContestScoreboardPolicy.computeContestMinutes(
                        update.contestStart(),
                        update.submittedTime()
                )),
                Long.toString(ContestScoreboardPolicy.PENALTY_PER_WRONG_MINUTES),
                Long.toString(ContestScoreboardPolicy.SCORE_SOLVED_WEIGHT),
                Long.toString(ContestScoreboardPolicy.SCORE_PENALTY_WEIGHT),
                Long.toString(update.userId())
        };
    }
}
