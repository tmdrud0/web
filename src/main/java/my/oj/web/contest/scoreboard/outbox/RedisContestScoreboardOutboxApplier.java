package my.oj.web.contest.scoreboard.outbox;

import io.lettuce.core.RedisCommandExecutionException;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.ContestScoreboardConstants;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RedisContestScoreboardOutboxApplier implements ContestScoreboardOutboxApplier {

    static final String SEQUENCE_KEY = "contest:scoreboard:outbox:seq";
    private static final String SUBMISSION_SEQUENCE_KEY = "contest:scoreboard:outbox:submission";
    private static final String SCOREBOARD_PREFIX = "contest:scoreboard:";

    private static final String APPLY_SCRIPT_TEXT = """
                    local function assertKeyType(key, expectedType)
                        local actualType = redis.call('type', key)['ok']
                        if actualType ~= 'none' and actualType ~= expectedType then
                            error('Unexpected Redis key type for ' .. key)
                        end
                    end

                    local function parseInteger(value, fieldName)
                        if not value then
                            return 0
                        end
                        if not string.match(value, '^-?%d+$') then
                            error('Invalid integer value for ' .. fieldName)
                        end
                        local parsed = tonumber(value)
                        if not parsed then
                            error('Invalid integer value for ' .. fieldName)
                        end
                        return parsed
                    end

                    local contestMinutes = tonumber(ARGV[4])
                    local wrongPenalty = tonumber(ARGV[5])
                    local solvedWeight = tonumber(ARGV[6])
                    local penaltyWeight = tonumber(ARGV[7])
                    local userId = tonumber(ARGV[8])
                    if not contestMinutes or not wrongPenalty or not solvedWeight or not penaltyWeight or not userId then
                        return redis.error_reply('Invalid scoreboard numeric argument')
                    end

                    assertKeyType(KEYS[1], 'string')
                    assertKeyType(KEYS[2], 'hash')
                    assertKeyType(KEYS[3], 'zset')
                    assertKeyType(KEYS[4], 'hash')
                    assertKeyType(KEYS[5], 'hash')
                    assertKeyType(KEYS[6], 'set')

                    local allocatorSequence = parseInteger(
                            redis.call('get', KEYS[1]),
                            'allocatorSequence')
                    if allocatorSequence < 0 then
                        return redis.error_reply('Invalid negative scoreboard allocator sequence')
                    end

                    local existingSequence = redis.call('hget', KEYS[2], ARGV[1])
                    if existingSequence then
                        local mappedSequence = parseInteger(existingSequence, 'submissionSequence')
                        if mappedSequence < 1 then
                            return redis.error_reply('Invalid scoreboard submission sequence')
                        end
                        if mappedSequence > allocatorSequence then
                            redis.call('set', KEYS[1], mappedSequence)
                        end
                    end

                    local alreadyProcessed = redis.call('sismember', KEYS[6], ARGV[2])
                    if alreadyProcessed == 1 then
                        if not existingSequence then
                            return redis.error_reply('Processed scoreboard event has no sequence mapping')
                        end
                        return tonumber(existingSequence)
                    end

                    local initialized = redis.call('hget', KEYS[4], 'initialized')
                    local accepted = redis.call('hget', KEYS[5], 'accepted')
                    if initialized and initialized ~= '1' then
                        return redis.error_reply('Invalid scoreboard initialized flag')
                    end
                    if accepted and accepted ~= '0' and accepted ~= '1' then
                        return redis.error_reply('Invalid scoreboard accepted flag')
                    end
                    local wrongAttempts = parseInteger(
                            redis.call('hget', KEYS[5], 'wrongAttempts'),
                            'wrongAttempts')
                    local currentSolved = parseInteger(
                            redis.call('hget', KEYS[4], 'solved'),
                            'solved')
                    local currentPenalty = parseInteger(
                            redis.call('hget', KEYS[4], 'penalty'),
                            'penalty')

                    local sequence = existingSequence
                    if not sequence then
                        sequence = redis.call('incr', KEYS[1])
                        redis.call('hset', KEYS[2], ARGV[1], sequence)
                    end

                    if ARGV[3] ~= 'PENDING' then
                        if not initialized then
                            redis.call('hset', KEYS[4],
                                    'solved', '0',
                                    'penalty', '0',
                                    'initialized', '1')
                            redis.call('zadd', KEYS[3], -userId, ARGV[8])
                        end

                        if accepted ~= '1' then
                            if ARGV[3] == 'ACCEPTED' then
                                local penaltyIncrement = contestMinutes + wrongAttempts * wrongPenalty
                                redis.call('hset', KEYS[5],
                                        'accepted', '1',
                                        'wrongAttempts', tostring(wrongAttempts))
                                local solved = redis.call('hincrby', KEYS[4], 'solved', 1)
                                local penalty = redis.call('hincrby', KEYS[4], 'penalty', penaltyIncrement)
                                local score = solved * solvedWeight - penalty * penaltyWeight - userId
                                redis.call('zadd', KEYS[3], score, ARGV[8])
                            else
                                redis.call('hset', KEYS[5], 'accepted', '0')
                                redis.call('hincrby', KEYS[5], 'wrongAttempts', 1)
                                local score = currentSolved * solvedWeight - currentPenalty * penaltyWeight - userId
                                redis.call('zadd', KEYS[3], score, ARGV[8])
                            end
                        end
                    end

                    redis.call('sadd', KEYS[6], ARGV[2])
                    return tonumber(sequence)
                    """;
    private static final RedisScript<Long> APPLY_SCRIPT = new DefaultRedisScript<>(
            APPLY_SCRIPT_TEXT,
            Long.class
    );

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
    public Long apply(Long eventId, ContestScoreboardOutboxPayload payload) {
        validate(eventId, payload);

        Long sequence = redisTemplate.execute(
                APPLY_SCRIPT,
                keys(payload),
                (Object[]) arguments(eventId, payload)
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
        safeRequests.forEach(request -> validate(request.eventId(), request.payload()));

        try {
            String loadedScriptSha = scriptSha();

            List<Object> pipelineResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (ApplyRequest request : safeRequests) {
                    connection.scriptingCommands().evalSha(
                            loadedScriptSha,
                            ReturnType.INTEGER,
                            6,
                            serializedKeysAndArguments(request.eventId(), request.payload())
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
                                APPLY_SCRIPT_TEXT.getBytes(StandardCharsets.UTF_8)
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

    private byte[][] serializedKeysAndArguments(Long eventId, ContestScoreboardOutboxPayload payload) {
        List<String> values = new ArrayList<>(14);
        values.addAll(keys(payload));
        values.addAll(List.of(arguments(eventId, payload)));
        return values.stream()
                .map(value -> redisTemplate.getStringSerializer().serialize(value))
                .toArray(byte[][]::new);
    }

    private static void validate(Long eventId, ContestScoreboardOutboxPayload payload) {
        if (eventId == null
                || payload == null
                || payload.contestSubmissionId() == null
                || payload.contestId() == null
                || payload.problemId() == null
                || payload.userId() == null
                || payload.result() == null) {
            throw new IllegalArgumentException("Scoreboard outbox event and payload fields are required");
        }
    }

    private static List<String> keys(ContestScoreboardOutboxPayload payload) {
        return List.of(
                SEQUENCE_KEY,
                SUBMISSION_SEQUENCE_KEY,
                rankingKey(payload.contestId()),
                summaryKey(payload.contestId(), payload.userId()),
                problemKey(payload.contestId(), payload.userId(), payload.problemId()),
                processedKey(payload.contestId())
        );
    }

    private static String[] arguments(Long eventId, ContestScoreboardOutboxPayload payload) {
        return new String[]{
                Long.toString(payload.contestSubmissionId()),
                Long.toString(eventId),
                payload.result().name(),
                Long.toString(contestMinutes(payload.contestStart(), payload.submittedTime())),
                Long.toString(ContestScoreboardConstants.PENALTY_PER_WRONG_MINUTES),
                Long.toString(ContestScoreboardConstants.SCORE_SOLVED_WEIGHT),
                Long.toString(ContestScoreboardConstants.SCORE_PENALTY_WEIGHT),
                Long.toString(payload.userId())
        };
    }

    private static long contestMinutes(LocalDateTime contestStart, LocalDateTime submittedTime) {
        if (contestStart == null || submittedTime == null) {
            return 0L;
        }
        long seconds = Duration.between(contestStart, submittedTime).toSeconds();
        if (seconds <= 0L) {
            return 0L;
        }
        return (seconds + 59L) / 60L;
    }

    private static String rankingKey(Long contestId) {
        return SCOREBOARD_PREFIX + contestId + ":ranking";
    }

    private static String summaryKey(Long contestId, Long userId) {
        return SCOREBOARD_PREFIX + contestId + ":user:" + userId + ":summary";
    }

    private static String problemKey(Long contestId, Long userId, Long problemId) {
        return SCOREBOARD_PREFIX + contestId + ":user:" + userId + ":problem:" + problemId;
    }

    private static String processedKey(Long contestId) {
        return SCOREBOARD_PREFIX + contestId + ":processed";
    }
}
