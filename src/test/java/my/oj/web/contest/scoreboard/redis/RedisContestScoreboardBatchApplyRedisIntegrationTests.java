package my.oj.web.contest.scoreboard.redis;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.CheckpointAdvance;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyRequest;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyResult;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyStatus;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceTracking;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batched script against a real Redis: the same events give the same scoreboard as the single-event
 * script, the batch stops at the first failure within and across chunks, and the checkpoint CAS refuses a
 * batch without writing anything.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisContestScoreboardBatchApplyRedisIntegrationTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 9, 1, 10, 0);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        flush();
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    // --- equivalence with the single-event script ---------------------------------------------------

    @Test
    void aBatchLeavesExactlyWhatTheSingleScriptLeavesEventByEvent() {
        assertBatchMatchesSingleScript(false);
    }

    /**
     * With the sequence on, the batch issues the same sequences to the same submissions: a duplicate is
     * issued none, and a mapping left above a rewound allocator is stepped over rather than reused.
     */
    @Test
    void theSequenceIsIssuedAndNeverReusedExactlyAsTheSingleScriptDoes() {
        // A mapping above the allocator, as a snapshot that kept the mapping and rewound the allocator
        // leaves it: the next issue for that submission must step over it in both scripts.
        assertBatchMatchesSingleScript(true);
    }

    private void assertBatchMatchesSingleScript(boolean trackSequence) {
        List<ApplyRequest> events = mixedEvents(new Random(7L), 240);
        ContestScoreboardSequenceTracking tracking = () -> trackSequence;

        seedRewoundAllocator(trackSequence);
        RedisContestScoreboardApplier single = applier(tracking, ContestScoreboardAppliedAtTracking.ENABLED, 100);
        List<Long> singleOffsets = new ArrayList<>();
        for (ApplyRequest event : events) {
            singleOffsets.add(single.apply(event));
        }
        Map<String, Object> expected = state();

        flush();
        seedRewoundAllocator(trackSequence);
        RedisContestScoreboardApplier batched = applier(tracking, ContestScoreboardAppliedAtTracking.ENABLED, 7);
        List<ApplyResult> results = batched.applyAll(events);

        assertThat(results).hasSize(events.size()).allMatch(ApplyResult::succeeded);
        assertThat(results.stream().map(ApplyResult::appliedOffset).toList()).isEqualTo(singleOffsets);
        assertThat(state()).isEqualTo(expected);
        if (trackSequence) {
            // The sequence each APPLIED result carries is the one the mapping now holds for it, so a
            // caller needs no second round trip to read it back.
            // (The last one issued per submission: an id reused in another contest is new there.)
            Map<String, String> lastIssued = new TreeMap<>();
            for (int i = 0; i < events.size(); i++) {
                if (results.get(i).sequence() != null) {
                    lastIssued.put(Long.toString(events.get(i).update().contestSubmissionId()),
                            Long.toString(results.get(i).sequence()));
                }
            }
            assertThat(lastIssued).isNotEmpty();
            Map<String, String> mapping = redisTemplate.<String, String>opsForHash()
                    .entries(RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY);
            assertThat(mapping).containsAllEntriesOf(lastIssued);
            assertThat(results.stream().filter(result -> result.status() == ApplyStatus.DUPLICATE))
                    .allMatch(result -> result.sequence() == null);
        }
    }

    private void seedRewoundAllocator(boolean trackSequence) {
        if (!trackSequence) {
            return;
        }
        redisTemplate.opsForValue().set(RedisContestScoreboardApplier.SEQUENCE_KEY, "3");
        redisTemplate.opsForHash().put(RedisContestScoreboardApplier.SUBMISSION_SEQUENCE_KEY, "5000", "9");
    }

    // --- stop at the first failure ------------------------------------------------------------------

    @Test
    void aFailedEventStopsTheBatchAndTheCheckpointEndsBeforeIt() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 100);
        corruptSummary(1L, 31L);

        List<ApplyResult> results = applier.applyAll(List.of(
                stream(10L, update(5001L, 1L, 30L, 1L, SubmissionResult.ACCEPTED, 5)),
                stream(11L, update(5002L, 1L, 30L, 2L, SubmissionResult.ACCEPTED, 6)),
                stream(12L, update(5003L, 1L, 31L, 1L, SubmissionResult.ACCEPTED, 7)),
                stream(13L, update(5004L, 1L, 32L, 1L, SubmissionResult.ACCEPTED, 8))
        ), ContestScoreboardApplier.NO_CHECKPOINT_FLOOR);

        assertThat(results).hasSize(3);
        assertThat(results.get(2).status()).isEqualTo(ApplyStatus.FAILED);
        assertThat(results.get(2).correlationId()).isEqualTo(12L);
        assertThat(results.get(2).errorMessage()).contains("Invalid integer value");
        assertThat(applier.currentStreamOffset()).isEqualTo(11L);
        assertThat(redisTemplate.opsForSet().members(ContestScoreboardRedisKeys.processed(1L)))
                .containsExactlyInAnyOrder("5001", "5002");
        assertThat(redisTemplate.opsForZSet().score(ContestScoreboardRedisKeys.ranking(1L), "32")).isNull();
    }

    @Test
    void aFailureInALaterChunkStopsTheBatchAcrossTheChunkBoundary() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 3);
        corruptSummary(1L, 36L);
        List<ApplyRequest> requests = new ArrayList<>();
        for (long i = 0; i < 12; i++) {
            requests.add(stream(100L + i, update(6000L + i, 1L, 30L + i, 1L, SubmissionResult.ACCEPTED, 5)));
        }

        List<ApplyResult> results = applier.applyAll(requests, ContestScoreboardApplier.NO_CHECKPOINT_FLOOR);

        // Chunks are [100..102] [103..105] [106..108] [109..111]; the event at 106 fails, so the third
        // chunk answers for that event only and the fourth is never sent.
        assertThat(results).hasSize(7);
        assertThat(results.subList(0, 6)).allMatch(ApplyResult::newlyApplied);
        assertThat(results.get(6).status()).isEqualTo(ApplyStatus.FAILED);
        assertThat(applier.currentStreamOffset()).isEqualTo(105L);
        assertThat(redisTemplate.opsForSet().size(ContestScoreboardRedisKeys.processed(1L))).isEqualTo(6L);
        assertThat(registry.get("contest.scoreboard.redis.apply.calls").summary().totalAmount()).isEqualTo(3.0);
    }

    // --- duplicates, rebuilds, round trips ----------------------------------------------------------

    @Test
    void aRedeliveryAndAProcessedSubmissionAreDuplicatesThatLeaveTheStandingsAlone() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 100);
        applier.applyAll(List.of(
                stream(20L, update(7001L, 2L, 40L, 1L, SubmissionResult.ACCEPTED, 12)),
                stream(21L, update(7002L, 2L, 41L, 1L, SubmissionResult.WRONG_ANSWER, 13))
        ));
        Map<String, Object> before = standings(2L);

        List<ApplyResult> results = applier.applyAll(List.of(
                // at or below the checkpoint: a resubscribe re-reading the anchor
                stream(21L, update(7002L, 2L, 41L, 1L, SubmissionResult.WRONG_ANSWER, 13)),
                // a new offset carrying a submission the contest already processed
                stream(22L, update(7001L, 2L, 40L, 1L, SubmissionResult.ACCEPTED, 12))
        ));

        assertThat(results).extracting(ApplyResult::status)
                .containsExactly(ApplyStatus.DUPLICATE, ApplyStatus.DUPLICATE);
        assertThat(results).extracting(ApplyResult::appliedOffset).containsExactly(21L, 22L);
        assertThat(standings(2L)).isEqualTo(before);
        assertThat(applier.currentStreamOffset()).isEqualTo(22L);
    }

    @Test
    void aRebuildBatchNeverMovesTheCheckpoint() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 2);
        applier.applyAll(List.of(stream(50L, update(8001L, 3L, 40L, 1L, SubmissionResult.ACCEPTED, 3))));

        List<ApplyResult> results = applier.applyAll(List.of(
                ApplyRequest.rebuild(8002L, update(8002L, 3L, 41L, 1L, SubmissionResult.ACCEPTED, 4)),
                ApplyRequest.rebuild(8003L, update(8003L, 3L, 42L, 1L, SubmissionResult.WRONG_ANSWER, 5)),
                ApplyRequest.rebuild(8004L, update(8004L, 3L, 43L, 1L, SubmissionResult.ACCEPTED, 6))
        ), 1_000L);

        // A rebuild is not a stream batch: the floor does not apply to it even when one is passed, and
        // it carries no offset that could move the checkpoint.
        assertThat(results).hasSize(3).allMatch(ApplyResult::newlyApplied);
        assertThat(results).extracting(ApplyResult::appliedOffset).containsOnly(50L);
        assertThat(applier.currentStreamOffset()).isEqualTo(50L);
        assertThat(redisTemplate.opsForSet().members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY))
                .containsExactly("8001");
    }

    @Test
    void aBatchCostsOneRedisCallPerChunk() {
        // Warm the script cache first, so the count below is EVALSHA only and not a NOSCRIPT fallback.
        applier(ContestScoreboardSequenceTracking.DISABLED, ContestScoreboardAppliedAtTracking.ENABLED, 100)
                .applyAll(List.of(stream(999L, update(89_999L, 99L, 1L, 1L, SubmissionResult.ACCEPTED, 1))));
        registry = new SimpleMeterRegistry();
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 100);
        List<ApplyRequest> requests = new ArrayList<>();
        for (long i = 0; i < 500; i++) {
            requests.add(stream(1_000L + i, update(90_000L + i, 4L, 1L + (i % 50), 1L + (i % 7),
                    i % 3 == 0 ? SubmissionResult.ACCEPTED : SubmissionResult.WRONG_ANSWER, (int) (i % 120))));
        }
        long evalsBefore = evalCalls();

        List<ApplyResult> results = applier.applyAll(requests);

        assertThat(results).hasSize(500).allMatch(ApplyResult::newlyApplied);
        assertThat(evalCalls() - evalsBefore).isEqualTo(5L);
        assertThat(registry.get("contest.scoreboard.redis.apply.calls").summary().totalAmount()).isEqualTo(5.0);
        assertThat(applier.currentStreamOffset()).isEqualTo(1_499L);
    }

    // --- checkpoint CAS -----------------------------------------------------------------------------

    @Test
    void aCheckpointBelowTheFloorRefusesTheBatchAndChangesNoKey() {
        RedisContestScoreboardApplier applier = applier(() -> true, ContestScoreboardAppliedAtTracking.ENABLED, 100);
        applier.applyAll(List.of(
                stream(900L, update(9001L, 5L, 40L, 1L, SubmissionResult.ACCEPTED, 3)),
                stream(901L, update(9002L, 5L, 41L, 1L, SubmissionResult.WRONG_ANSWER, 4))
        ));
        Map<String, Object> before = state();

        List<ApplyResult> results = applier.applyAll(List.of(
                stream(1001L, update(9003L, 5L, 42L, 1L, SubmissionResult.ACCEPTED, 5)),
                stream(1002L, update(9002L, 5L, 41L, 1L, SubmissionResult.WRONG_ANSWER, 4))
        ), 1_000L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ApplyStatus.ROLLBACK);
            assertThat(result.appliedOffset()).isEqualTo(901L);
            assertThat(result.correlationId()).isEqualTo(1001L);
        });
        // Scoreboard, checkpoint, processed set, db-pending and sequence: nothing moved.
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aCheckpointAtOrAboveTheFloorIsAppliedNormally() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 100);
        applier.applyAll(List.of(stream(1000L, update(9101L, 6L, 40L, 1L, SubmissionResult.ACCEPTED, 3))));

        List<ApplyResult> atFloor = applier.applyAll(List.of(
                stream(1001L, update(9102L, 6L, 41L, 1L, SubmissionResult.ACCEPTED, 4))), 1_000L);
        List<ApplyResult> belowStored = applier.applyAll(List.of(
                stream(1002L, update(9103L, 6L, 42L, 1L, SubmissionResult.ACCEPTED, 5))), 900L);

        assertThat(atFloor).singleElement().satisfies(result -> assertThat(result.newlyApplied()).isTrue());
        assertThat(belowStored).singleElement().satisfies(result -> assertThat(result.newlyApplied()).isTrue());
        assertThat(applier.currentStreamOffset()).isEqualTo(1002L);
    }

    @Test
    void noFloorSkipsTheCheck() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 100);

        List<ApplyResult> results = applier.applyAll(List.of(
                stream(5L, update(9201L, 7L, 40L, 1L, SubmissionResult.ACCEPTED, 3), CheckpointAdvance.ANCHOR)),
                ContestScoreboardApplier.NO_CHECKPOINT_FLOOR);

        assertThat(results).singleElement().satisfies(result -> assertThat(result.newlyApplied()).isTrue());
        assertThat(applier.currentStreamOffset()).isEqualTo(5L);
    }

    @Test
    void aRollbackBetweenTwoChunksOfOneBatchIsRefusedAtTheSecondChunk() {
        // The second chunk is checked against the checkpoint the first one wrote. Simulated by a floor
        // that the first chunk's own checkpoint satisfies and a restore that lands before the second.
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.ENABLED, 2);
        applier.applyAll(List.of(stream(10L, update(9301L, 8L, 40L, 1L, SubmissionResult.ACCEPTED, 3))));
        RedisScoreboardSnapshot snapshot = RedisScoreboardSnapshot.take(redisTemplate);
        RestoringTemplate restoring = new RestoringTemplate(connectionFactory, snapshot, 1);
        RedisContestScoreboardApplier racing = new RedisContestScoreboardApplier(restoring,
                new RedisTemplateContestRedisKeyValueClient(restoring), new RedisContestScoreboardApplyMetrics(registry),
                ContestScoreboardSequenceTracking.DISABLED, ContestScoreboardAppliedAtTracking.ENABLED, 2);

        List<ApplyResult> results = racing.applyAll(List.of(
                stream(11L, update(9302L, 8L, 41L, 1L, SubmissionResult.ACCEPTED, 4)),
                stream(12L, update(9303L, 8L, 42L, 1L, SubmissionResult.ACCEPTED, 5)),
                stream(13L, update(9304L, 8L, 43L, 1L, SubmissionResult.ACCEPTED, 6))
        ), 10L);

        assertThat(results).extracting(ApplyResult::status)
                .containsExactly(ApplyStatus.APPLIED, ApplyStatus.APPLIED, ApplyStatus.ROLLBACK);
        assertThat(results.get(2).appliedOffset()).isEqualTo(10L);
        assertThat(applier.currentStreamOffset()).isEqualTo(10L);
        assertThat(redisTemplate.opsForSet().isMember(ContestScoreboardRedisKeys.processed(8L), "9304")).isFalse();
    }

    // --- applied-at tracking off --------------------------------------------------------------------

    @Test
    void withAppliedAtTrackingOffNoStreamRequestTouchesTheDbPendingSet() {
        RedisContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED,
                ContestScoreboardAppliedAtTracking.DISABLED, 100);
        // A set left over from before the switch: nothing reads it and nothing adds to it.
        redisTemplate.opsForSet().add(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY, "1", "2");

        List<ApplyResult> results = applier.applyAll(List.of(
                stream(30L, update(9401L, 9L, 40L, 1L, SubmissionResult.ACCEPTED, 3)),
                stream(31L, update(9401L, 9L, 40L, 1L, SubmissionResult.ACCEPTED, 3)),
                stream(32L, update(9402L, 9L, 41L, 1L, SubmissionResult.WRONG_ANSWER, 4))
        ), ContestScoreboardApplier.NO_CHECKPOINT_FLOOR);

        assertThat(results).extracting(ApplyResult::status)
                .containsExactly(ApplyStatus.APPLIED, ApplyStatus.DUPLICATE, ApplyStatus.APPLIED);
        assertThat(applier.currentStreamOffset()).isEqualTo(32L);
        assertThat(redisTemplate.opsForSet().members(RedisContestScoreboardApplier.STREAM_DB_PENDING_KEY))
                .containsExactlyInAnyOrder("1", "2");
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** A template that restores a snapshot right before its {@code n}-th script call - a rollback landing mid-batch. */
    private static final class RestoringTemplate extends StringRedisTemplate {
        private final RedisScoreboardSnapshot snapshot;
        private final int restoreBeforeCall;
        private int calls;

        RestoringTemplate(LettuceConnectionFactory factory, RedisScoreboardSnapshot snapshot, int restoreAfterCalls) {
            super(factory);
            this.snapshot = snapshot;
            this.restoreBeforeCall = restoreAfterCalls + 1;
            afterPropertiesSet();
        }

        @Override
        public <T> T execute(org.springframework.data.redis.core.script.RedisScript<T> script,
                             List<String> keys, Object... args) {
            calls++;
            if (calls == restoreBeforeCall) {
                snapshot.restoreInto(this);
            }
            return super.execute(script, keys, args);
        }
    }

    private RedisContestScoreboardApplier applier(ContestScoreboardSequenceTracking sequenceTracking,
                                                  ContestScoreboardAppliedAtTracking appliedAtTracking,
                                                  int chunkSize) {
        return new RedisContestScoreboardApplier(redisTemplate, new RedisTemplateContestRedisKeyValueClient(redisTemplate),
                new RedisContestScoreboardApplyMetrics(registry), sequenceTracking, appliedAtTracking, chunkSize);
    }

    /**
     * Stream events over three contests, a handful of users and problems, both verdicts, sparse offsets,
     * re-deliveries at or below the checkpoint, repeated submissions under a new offset, a PENDING result
     * and a few rebuild requests - everything the single-event script distinguishes.
     */
    private static List<ApplyRequest> mixedEvents(Random random, int count) {
        List<ApplyRequest> events = new ArrayList<>();
        List<ContestScoreboardUpdate> seen = new ArrayList<>();
        long offset = 0L;
        long submissionId = 4_990L;
        for (int i = 0; i < count; i++) {
            int kind = random.nextInt(20);
            if (kind == 0 && offset > 3) {
                // Re-delivery at or below the checkpoint of something already applied.
                ContestScoreboardUpdate old = seen.get(random.nextInt(seen.size()));
                events.add(stream(offset - random.nextInt(3), old));
                continue;
            }
            if (kind == 1 && !seen.isEmpty()) {
                // A processed submission under a new offset.
                offset += 1 + random.nextInt(3);
                events.add(stream(offset, seen.get(random.nextInt(seen.size()))));
                continue;
            }
            submissionId += 1 + random.nextInt(2);
            SubmissionResult result = kind == 2
                    ? SubmissionResult.PENDING
                    : (random.nextInt(3) == 0 ? SubmissionResult.ACCEPTED : SubmissionResult.WRONG_ANSWER);
            ContestScoreboardUpdate update = update(submissionId, 1L + random.nextInt(3), 1L + random.nextInt(6),
                    1L + random.nextInt(4), result, random.nextInt(180));
            seen.add(update);
            if (kind == 3) {
                events.add(ApplyRequest.rebuild(submissionId, update));
                continue;
            }
            offset += 1 + random.nextInt(3);
            events.add(stream(offset, update, events.isEmpty() ? CheckpointAdvance.ANCHOR : CheckpointAdvance.CONTINUE));
        }
        // The submission the rewound mapping belongs to, so the step-over rule is exercised.
        offset += 1;
        events.add(stream(offset, update(5_000L, 1L, 1L, 1L, SubmissionResult.ACCEPTED, 30)));
        return events;
    }

    private static ApplyRequest stream(long offset, ContestScoreboardUpdate update) {
        return ApplyRequest.stream(offset, update);
    }

    private static ApplyRequest stream(long offset, ContestScoreboardUpdate update, CheckpointAdvance advance) {
        return ApplyRequest.stream(offset, update, advance);
    }

    private static ContestScoreboardUpdate update(long submissionId, long contestId, long userId, long problemId,
                                                  SubmissionResult result, int minutes) {
        return new ContestScoreboardUpdate(submissionId, contestId, problemId, userId, CONTEST_START,
                CONTEST_START.plusMinutes(minutes), result, CONTEST_START.plusMinutes(minutes + 1L));
    }

    private void corruptSummary(long contestId, long userId) {
        redisTemplate.opsForHash().put(ContestScoreboardRedisKeys.summary(contestId, userId), "initialized", "1");
        redisTemplate.opsForHash().put(ContestScoreboardRedisKeys.summary(contestId, userId), "solved", "not-a-number");
    }

    private Map<String, Object> standings(long contestId) {
        Map<String, Object> state = state();
        state.keySet().removeIf(key -> !key.startsWith("contest:scoreboard:" + contestId + ":")
                || key.endsWith(":processed"));
        return state;
    }

    /** Every scoreboard key, read by type into a value that compares by content. */
    private Map<String, Object> state() {
        Set<String> keys = redisTemplate.keys("contest:scoreboard:*");
        Map<String, Object> state = new TreeMap<>();
        for (String key : keys) {
            String type = redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.keyCommands().type(key.getBytes()).code());
            switch (type) {
                case "string" -> state.put(key, redisTemplate.opsForValue().get(key));
                case "set" -> state.put(key, new TreeSet<>(redisTemplate.opsForSet().members(key)));
                case "hash" -> state.put(key, new TreeMap<>(redisTemplate.<String, String>opsForHash().entries(key)));
                case "zset" -> {
                    Map<String, Double> scores = new TreeMap<>();
                    for (ZSetOperations.TypedTuple<String> tuple
                            : redisTemplate.opsForZSet().rangeWithScores(key, 0, -1)) {
                        scores.put(tuple.getValue(), tuple.getScore());
                    }
                    state.put(key, scores);
                }
                default -> state.put(key, type);
            }
        }
        return state;
    }

    private long evalCalls() {
        String info = redisTemplate.execute((RedisCallback<String>) connection ->
                connection.serverCommands().info("commandstats").getProperty("cmdstat_evalsha", "")
                        + ";" + connection.serverCommands().info("commandstats").getProperty("cmdstat_eval", ""));
        long total = 0L;
        for (String part : info.split(";")) {
            int start = part.indexOf("calls=");
            if (start >= 0) {
                int end = part.indexOf(',', start);
                total += Long.parseLong(part.substring(start + 6, end < 0 ? part.length() : end));
            }
        }
        return total;
    }

    private void flush() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            RedisConnection raw = connection;
            raw.serverCommands().flushDb();
            return null;
        });
    }
}
