package my.oj.web.contest.scoreboard.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardPolicy;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RedisContestScoreboardApplier implements ContestScoreboardApplier {

    public static final String STREAM_OFFSET_KEY = ContestScoreboardRedisKeys.STREAM_OFFSET;
    public static final String STREAM_DB_PENDING_KEY = ContestScoreboardRedisKeys.STREAM_DB_PENDING;
    public static final String SEQUENCE_KEY = ContestScoreboardRedisKeys.SEQUENCE;
    public static final String SUBMISSION_SEQUENCE_KEY = ContestScoreboardRedisKeys.SUBMISSION_SEQUENCE;

    /**
     * Events per {@code EVAL} when nothing else is configured
     * ({@code contest.scoreboard.redis.apply-chunk-size}).
     *
     * <p>A script blocks every Redis client while it runs, so a chunk is sized to stay around a
     * millisecond: one event is on the order of ten to fifteen Redis commands inside the script (key
     * type checks, the summary and problem reads, the writes), a few microseconds each, so a hundred
     * events hold Redis for roughly a millisecond - far below the 5 s script time limit and below the
     * latency the scoreboard's readers are measured at - while a 500-event stream batch still costs five
     * round trips instead of five hundred.</p>
     */
    public static final int DEFAULT_CHUNK_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final ContestRedisKeyValueClient redisClient;
    private final RedisContestScoreboardApplyMetrics metrics;
    private final ContestScoreboardSequenceTracking sequenceTracking;
    private final ContestScoreboardAppliedAtTracking appliedAtTracking;
    private final int chunkSize;

    public RedisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                         ContestRedisKeyValueClient redisClient) {
        this(redisTemplate, redisClient,
                new RedisContestScoreboardApplyMetrics(new CompositeMeterRegistry()));
    }

    public RedisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                         ContestRedisKeyValueClient redisClient,
                                         RedisContestScoreboardApplyMetrics metrics) {
        this(redisTemplate, redisClient, metrics, ContestScoreboardSequenceTracking.DISABLED);
    }

    public RedisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                         ContestRedisKeyValueClient redisClient,
                                         RedisContestScoreboardApplyMetrics metrics,
                                         ContestScoreboardSequenceTracking sequenceTracking) {
        this(redisTemplate, redisClient, metrics, sequenceTracking,
                ContestScoreboardAppliedAtTracking.ENABLED, DEFAULT_CHUNK_SIZE);
    }

    /**
     * @param appliedAtTracking whether a stream request records its submission in the db-pending set
     *                          that the MySQL {@code scoreboard_applied_at} completion repairs from
     * @param chunkSize         how many events one {@code EVAL} applies at most. A batch larger than this
     *                          is sent as several scripts, in order, and still stops at the first failure
     */
    public RedisContestScoreboardApplier(StringRedisTemplate redisTemplate,
                                         ContestRedisKeyValueClient redisClient,
                                         RedisContestScoreboardApplyMetrics metrics,
                                         ContestScoreboardSequenceTracking sequenceTracking,
                                         ContestScoreboardAppliedAtTracking appliedAtTracking,
                                         int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("Scoreboard apply chunk size must be at least 1: " + chunkSize);
        }
        this.redisTemplate = redisTemplate;
        this.chunkSize = chunkSize;
        this.appliedAtTracking = appliedAtTracking == null
                ? ContestScoreboardAppliedAtTracking.ENABLED
                : appliedAtTracking;
        this.redisClient = redisClient;
        this.metrics = metrics;
        this.sequenceTracking = sequenceTracking == null
                ? ContestScoreboardSequenceTracking.DISABLED
                : sequenceTracking;
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
                    (Object[]) arguments(request, sequenceTracking.enabled())
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
     * Applies the batch through {@link ContestScoreboardRedisBatchScript}, one {@code EVAL} per chunk.
     *
     * <p>Chunks are sent in order and the batch stops at the first chunk whose reply is not a success
     * all the way through: the script itself stops at the first failed event, so the failure and
     * everything before it are answered for and nothing after it was attempted - within a chunk and,
     * because the next chunk is never sent, across chunk boundaries too.</p>
     *
     * <p>The checkpoint floor is carried from chunk to chunk. The first chunk is checked against the
     * caller's floor; every later chunk of a stream batch against the checkpoint the previous chunk
     * left, which this call wrote itself. A rollback that lands between two chunks of one batch is
     * therefore refused like one that lands before the first.</p>
     */
    @Override
    public List<ApplyResult> applyAll(List<ApplyRequest> requests, long expectedCheckpointFloor) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        long startedNanos = System.nanoTime();
        int redisCalls = 0;
        List<ApplyRequest> safeRequests = new ArrayList<>(requests);
        List<ApplyResult> results = new ArrayList<>(safeRequests.size());
        long floor = expectedCheckpointFloor;
        try {
            int index = 0;
            while (index < safeRequests.size()) {
                List<ApplyRequest> chunk = new ArrayList<>(Math.min(chunkSize, safeRequests.size() - index));
                ApplyResult invalid = null;
                while (index < safeRequests.size() && chunk.size() < chunkSize) {
                    ApplyRequest request = safeRequests.get(index);
                    String problem = validationProblem(request);
                    if (problem != null) {
                        invalid = ApplyResult.failure(request == null ? -1L : request.correlationId(), problem);
                        break;
                    }
                    chunk.add(request);
                    index++;
                }
                if (!chunk.isEmpty()) {
                    redisCalls++;
                    List<ApplyResult> chunkResults = executeChunk(chunk, floor);
                    results.addAll(chunkResults);
                    ApplyResult last = chunkResults.isEmpty() ? null : chunkResults.get(chunkResults.size() - 1);
                    if (last == null || !last.succeeded() || chunkResults.size() != chunk.size()) {
                        return List.copyOf(results);
                    }
                    if (hasStreamOffset(chunk) && last.appliedOffset() != null) {
                        floor = Math.max(floor, last.appliedOffset());
                    }
                }
                if (invalid != null) {
                    results.add(invalid);
                    return List.copyOf(results);
                }
            }
            return List.copyOf(results);
        } finally {
            metrics.recordBatch(Duration.ofNanos(System.nanoTime() - startedNanos), redisCalls);
        }
    }

    private List<ApplyResult> executeChunk(List<ApplyRequest> chunk, long floor) {
        List<String> keys = new ArrayList<>(ContestScoreboardRedisBatchScript.GLOBAL_KEYS
                + chunk.size() * ContestScoreboardRedisBatchScript.KEYS_PER_EVENT);
        keys.add(STREAM_OFFSET_KEY);
        keys.add(STREAM_DB_PENDING_KEY);
        keys.add(SEQUENCE_KEY);
        keys.add(SUBMISSION_SEQUENCE_KEY);
        String[] arguments = new String[ContestScoreboardRedisBatchScript.HEADER_ARGS
                + chunk.size() * ContestScoreboardRedisBatchScript.ARGS_PER_EVENT];
        arguments[0] = Integer.toString(chunk.size());
        // A rebuild carries no offset and is never a claim about the consumer's position, so it is not
        // checked against a floor even when a caller passes one.
        arguments[1] = Long.toString(floor < 0L || !hasStreamOffset(chunk) ? NO_CHECKPOINT_FLOOR : floor);
        arguments[2] = sequenceTracking.enabled() ? "1" : "0";
        arguments[3] = appliedAtTracking.enabled() ? "1" : "0";
        arguments[4] = Long.toString(ContestScoreboardPolicy.PENALTY_PER_WRONG_MINUTES);
        arguments[5] = Long.toString(ContestScoreboardPolicy.SCORE_SOLVED_WEIGHT);
        arguments[6] = Long.toString(ContestScoreboardPolicy.SCORE_PENALTY_WEIGHT);
        int position = ContestScoreboardRedisBatchScript.HEADER_ARGS;
        for (ApplyRequest request : chunk) {
            ContestScoreboardUpdate update = request.update();
            keys.add(ContestScoreboardRedisKeys.ranking(update.contestId()));
            keys.add(ContestScoreboardRedisKeys.summary(update.contestId(), update.userId()));
            keys.add(ContestScoreboardRedisKeys.problem(update.contestId(), update.userId(), update.problemId()));
            keys.add(ContestScoreboardRedisKeys.processed(update.contestId()));
            arguments[position++] = request.streamOffset() == null ? "" : Long.toString(request.streamOffset());
            arguments[position++] = request.advance().token();
            arguments[position++] = Long.toString(update.contestSubmissionId());
            arguments[position++] = update.result().name();
            arguments[position++] = Long.toString(ContestScoreboardPolicy.computeContestMinutes(
                    update.contestStart(), update.submittedTime()));
            arguments[position++] = Long.toString(update.userId());
        }

        List<?> reply;
        try {
            reply = redisTemplate.execute(ContestScoreboardRedisBatchScript.APPLY, keys, (Object[]) arguments);
        } catch (RuntimeException failure) {
            // The whole chunk is unanswered: a transport failure, or a refusal of the script itself.
            // Reported at the chunk's first request, which is the conservative place - the unapplied
            // range then starts no later than anything this chunk may have written.
            if (hasCommandExecutionFailure(failure)) {
                metrics.recordLuaError(failure);
            }
            return List.of(ApplyResult.failure(chunk.get(0).correlationId(),
                    ContestScoreboardApplier.errorMessage(failure)));
        }
        if (reply == null || reply.isEmpty()) {
            return List.of(ApplyResult.failure(chunk.get(0).correlationId(),
                    "Redis scoreboard batch script returned no result"));
        }
        List<ApplyResult> results = new ArrayList<>(reply.size());
        for (int i = 0; i < reply.size() && i < chunk.size(); i++) {
            ApplyResult result = toResult(chunk.get(i).correlationId(), reply.get(i));
            results.add(result);
            if (!result.succeeded()) {
                break;
            }
        }
        return results;
    }

    private ApplyResult toResult(long correlationId, Object entry) {
        if (!(entry instanceof List<?> fields) || fields.size() < 3) {
            return ApplyResult.failure(correlationId, "Malformed Redis scoreboard batch reply: " + entry);
        }
        long status = asLong(fields.get(0));
        long offset = asLong(fields.get(1));
        long sequence = asLong(fields.get(2));
        if (status == ContestScoreboardRedisBatchScript.APPLIED) {
            return ApplyResult.applied(correlationId, offset, sequence < 0L ? null : sequence);
        }
        if (status == ContestScoreboardRedisBatchScript.DUPLICATE) {
            return ApplyResult.duplicate(correlationId, offset);
        }
        if (status == ContestScoreboardRedisBatchScript.ROLLBACK) {
            return ApplyResult.rollback(correlationId, offset);
        }
        String message = fields.size() > 3 && fields.get(3) != null
                ? String.valueOf(fields.get(3))
                : "Redis scoreboard event failed";
        metrics.recordLuaError(message);
        return ApplyResult.failure(correlationId, message);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static boolean hasStreamOffset(List<ApplyRequest> chunk) {
        return chunk.stream().anyMatch(request -> request.streamOffset() != null);
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
        if (validationProblem(request) != null) {
            throw new IllegalArgumentException("Scoreboard event and update fields are required");
        }
    }

    /** Why a request cannot be sent, as a failed apply used to report it; {@code null} if it can. */
    private static String validationProblem(ApplyRequest request) {
        ContestScoreboardUpdate update = request == null ? null : request.update();
        if (request == null
                || update.contestSubmissionId() == null
                || update.contestId() == null
                || update.problemId() == null
                || update.userId() == null
                || update.result() == null) {
            return "IllegalArgumentException: Scoreboard event and update fields are required";
        }
        return null;
    }

    private static List<String> keys(ContestScoreboardUpdate update) {
        return List.of(
                STREAM_OFFSET_KEY,
                STREAM_DB_PENDING_KEY,
                ContestScoreboardRedisKeys.ranking(update.contestId()),
                ContestScoreboardRedisKeys.summary(update.contestId(), update.userId()),
                ContestScoreboardRedisKeys.problem(update.contestId(), update.userId(), update.problemId()),
                ContestScoreboardRedisKeys.processed(update.contestId()),
                SEQUENCE_KEY,
                SUBMISSION_SEQUENCE_KEY
        );
    }

    private static String[] arguments(ApplyRequest request, boolean trackSequence) {
        ContestScoreboardUpdate update = request.update();
        return new String[]{
                request.streamOffset() == null ? "" : Long.toString(request.streamOffset()),
                request.advance().token(),
                Long.toString(update.contestSubmissionId()),
                update.result().name(),
                Long.toString(ContestScoreboardPolicy.computeContestMinutes(
                        update.contestStart(),
                        update.submittedTime()
                )),
                Long.toString(ContestScoreboardPolicy.PENALTY_PER_WRONG_MINUTES),
                Long.toString(ContestScoreboardPolicy.SCORE_SOLVED_WEIGHT),
                Long.toString(ContestScoreboardPolicy.SCORE_PENALTY_WEIGHT),
                Long.toString(update.userId()),
                trackSequence ? "1" : "0"
        };
    }
}
