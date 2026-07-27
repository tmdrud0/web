package my.oj.web.contest.scoreboard.redis;

import io.lettuce.core.RedisCommandExecutionException;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxApplier;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RedisContestScoreboardOutboxApplier implements ContestScoreboardOutboxApplier {

    static final String SEQUENCE_KEY = ContestScoreboardRedisKeys.OUTBOX_SEQUENCE;

    private final StringRedisTemplate redisTemplate;
    private volatile String scriptSha;

    public RedisContestScoreboardOutboxApplier(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        if (redisTemplate.getConnectionFactory() instanceof LettuceConnectionFactory connectionFactory) {
            connectionFactory.setPipeliningFlushPolicy(
                    LettuceConnection.PipeliningFlushPolicy.flushOnClose()
            );
        }
    }

    @Override
    public Long apply(Long eventId, ContestScoreboardUpdate update) {
        validate(eventId, update);

        Long sequence = redisTemplate.execute(
                ContestScoreboardRedisScript.APPLY,
                keys(update),
                (Object[]) arguments(eventId, update)
        );
        if (sequence == null) {
            throw new IllegalStateException("Redis scoreboard script returned no sequence");
        }
        return sequence;
    }

    @Override
    public List<ApplyResult> applyAll(List<ApplyRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ApplyRequest> safeRequests = List.copyOf(requests);
        safeRequests.forEach(request -> validate(request.eventId(), request.update()));

        try {
            String loadedScriptSha = scriptSha();

            List<Object> pipelineResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (ApplyRequest request : safeRequests) {
                    connection.scriptingCommands().evalSha(
                            loadedScriptSha,
                            ReturnType.INTEGER,
                            6,
                            serializedKeysAndArguments(request.eventId(), request.update())
                    );
                }
                return null;
            });
            if (pipelineResults.size() != safeRequests.size()) {
                log.warn(
                        "Redis scoreboard pipeline returned {} results for {} events",
                        pipelineResults.size(),
                        safeRequests.size()
                );
                return failedResults(
                        safeRequests,
                        "Redis pipeline returned a different number of results than requested"
                );
            }

            List<ApplyResult> results = new ArrayList<>(safeRequests.size());
            for (int index = 0; index < safeRequests.size(); index++) {
                Object result = pipelineResults.get(index);
                if (!(result instanceof Number number)) {
                    log.warn(
                            "Redis scoreboard pipeline returned an unexpected result type at index {}: {}",
                            index,
                            result == null ? "null" : result.getClass().getName()
                    );
                    return failedResults(
                            safeRequests,
                            "Redis pipeline returned an unexpected result type"
                    );
                }
                results.add(ApplyResult.success(
                        safeRequests.get(index).eventId(),
                        number.longValue()
                ));
            }
            return List.copyOf(results);
        } catch (RuntimeException exception) {
            if (hasCommandExecutionFailure(exception)) {
                log.warn(
                        "Redis scoreboard pipeline command failed; classifying {} events individually",
                        safeRequests.size(),
                        exception
                );
                return ContestScoreboardOutboxApplier.super.applyAll(safeRequests);
            }
            log.warn(
                    "Redis scoreboard pipeline failed; deferring {} events for retry",
                    safeRequests.size(),
                    exception
            );
            return failedResults(safeRequests, errorMessage(exception));
        }
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

    private static List<ApplyResult> failedResults(List<ApplyRequest> requests, String errorMessage) {
        return requests.stream()
                .map(request -> ApplyResult.failure(request.eventId(), errorMessage))
                .toList();
    }

    private static String errorMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private String scriptSha() {
        String loaded = scriptSha;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (scriptSha == null) {
                scriptSha = redisTemplate.execute((RedisCallback<String>) connection ->
                        connection.scriptingCommands().scriptLoad(
                                ContestScoreboardRedisScript.TEXT.getBytes(StandardCharsets.UTF_8)
                        )
                );
                if (scriptSha == null || scriptSha.isBlank()) {
                    throw new IllegalStateException("Redis scoreboard script could not be loaded");
                }
            }
            return scriptSha;
        }
    }

    @Override
    public long currentSequence() {
        String value = redisTemplate.opsForValue().get(SEQUENCE_KEY);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private byte[][] serializedKeysAndArguments(Long eventId, ContestScoreboardUpdate update) {
        List<String> values = new ArrayList<>(14);
        values.addAll(keys(update));
        values.addAll(List.of(arguments(eventId, update)));
        return values.stream()
                .map(value -> redisTemplate.getStringSerializer().serialize(value))
                .toArray(byte[][]::new);
    }

    private static void validate(Long eventId, ContestScoreboardUpdate update) {
        if (eventId == null
                || update == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            throw new IllegalArgumentException("Scoreboard outbox event and update fields are required");
        }
    }

    private static List<String> keys(ContestScoreboardUpdate update) {
        return List.of(
                SEQUENCE_KEY,
                ContestScoreboardRedisKeys.OUTBOX_SUBMISSION_SEQUENCE,
                ContestScoreboardRedisKeys.ranking(update.contestId()),
                ContestScoreboardRedisKeys.summary(update.contestId(), update.userId()),
                ContestScoreboardRedisKeys.problem(update.contestId(), update.userId(), update.problemId()),
                ContestScoreboardRedisKeys.processed(update.contestId())
        );
    }

    private static String[] arguments(Long eventId, ContestScoreboardUpdate update) {
        return new String[]{
                Long.toString(update.contestSubmissionId()),
                Long.toString(eventId),
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
