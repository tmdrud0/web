package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplier;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardApplyMetrics;
import my.oj.web.contest.scoreboard.redis.RedisScoreboardSnapshot;
import my.oj.web.contest.scoreboard.redis.RedisTemplateContestRedisKeyValueClient;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The race the checkpoint CAS closes, reproduced against a real Redis.
 *
 * <p>{@code resolveAdvance} reads the checkpoint outside the apply lock and decides the batch is an
 * ordinary forward step. If Redis is then restored to an older snapshot before the batch is applied -
 * checkpoint 19 back to 9 - the script sees only "incoming 20 &gt; stored 9" and applies, moving the
 * checkpoint to 29 over 10..19. The standings never get those results back: the checkpoint is past them,
 * so a resubscribe re-reads nothing, and the JVM's applied watermark is a running maximum, so the
 * supervisor's {@code stored &lt; applied} comparison sees 29 against 29 and nothing wrong.</p>
 *
 * <p>With the CAS the batch carries the checkpoint this JVM already observed for its position (19), the
 * script finds 9, refuses the whole batch without writing anything, and the resubscribe at the stored
 * checkpoint re-reads 10..29.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ContestScoreboardStreamCheckpointRaceRedisIntegrationTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final long CONTEST_ID = 42L;
    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 9, 1, 10, 0);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisContestScoreboardApplier applier;
    private ContestScoreboardStreamPosition position;
    private RestoringLock lock;
    private ContestScoreboardStreamProcessor processor;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        applier = new RedisContestScoreboardApplier(redisTemplate,
                new RedisTemplateContestRedisKeyValueClient(redisTemplate),
                new RedisContestScoreboardApplyMetrics(registry));
        position = new ContestScoreboardStreamPosition();
        ContestScoreboardRecoveryStrategy strategy = mock(ContestScoreboardRecoveryStrategy.class);
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.STREAM_OFFSET);
        // stream-offset's live-path answer to a rollback inside what it applied: not its question.
        lenient().when(strategy.rebuildHistory(any()))
                .thenReturn(ContestScoreboardRecoveryStrategy.Outcome.RETRYABLE_FAILURE);
        lock = new RestoringLock();
        ContestScoreboardAppliedAtCompletion completion = new ContestScoreboardAppliedAtCompletion(
                redisTemplate, mock(ContestScoreboardAppliedMarker.class), properties());
        processor = new ContestScoreboardStreamProcessor(applier, completion, position, strategy,
                new ContestScoreboardStreamMetrics(registry), lock);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void aSnapshotRestoredBetweenTheAnchorDecisionAndTheApplyDoesNotSkipTheLostRange() {
        processor.process(events(0, 9));
        RedisScoreboardSnapshot snapshot = RedisScoreboardSnapshot.take(redisTemplate);
        processor.process(events(10, 19));
        assertThat(applier.currentStreamOffset()).isEqualTo(19L);

        // Redis goes back to the snapshot after resolveAdvance has read 19 and before the batch is applied.
        lock.restoreOnNextLock(() -> snapshot.restoreInto(redisTemplate));
        Throwable refused = catchThrowable(() -> processor.process(events(20, 29)));

        // The checkpoint was not carried over 10..19: it is still where the snapshot left it.
        assertThat(applier.currentStreamOffset())
                .as("the batch applied after the restore must not move the checkpoint past the lost range")
                .isEqualTo(9L);

        // What the lifecycle does next: resubscribe at the stored checkpoint, inclusive, and read forward.
        position.consumerRestarted();
        processor.process(events(9, 29));

        assertThat(applier.currentStreamOffset()).isEqualTo(29L);
        assertThat(processed())
                .as("every result 0..29 is on the scoreboard after the resubscribe")
                .hasSize(30);
        assertThat(refused)
                .as("the batch that met the restored checkpoint is refused, not applied")
                .isInstanceOf(ContestScoreboardCheckpointRegressedException.class);
        // A rollback is not a failed batch: nothing is held back as unapplied.
        assertThat(position.unappliedFrom()).isEqualTo(-1L);
    }

    private Set<String> processed() {
        return redisTemplate.opsForSet().members("contest:scoreboard:" + CONTEST_ID + ":processed");
    }

    private static List<ContestScoreboardStreamEvent> events(long fromOffset, long toOffset) {
        List<ContestScoreboardStreamEvent> events = new ArrayList<>();
        for (long offset = fromOffset; offset <= toOffset; offset++) {
            events.add(new ContestScoreboardStreamEvent(offset, new ContestJudgeResultStreamMessage(
                    ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                    10_000L + offset,
                    CONTEST_ID,
                    1L + offset % 3,
                    100L + offset % 7,
                    CONTEST_START,
                    CONTEST_START.plusMinutes(offset),
                    CONTEST_START.plusMinutes(offset + 1),
                    offset % 2 == 0 ? SubmissionResult.ACCEPTED : SubmissionResult.WRONG_ANSWER)));
        }
        return events;
    }

    private static ContestScoreboardStreamConsumerProperties properties() {
        return new ContestScoreboardStreamConsumerProperties(500, 500, Duration.ofMillis(50), Duration.ofMillis(1),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096);
    }

    /** The apply lock, with a hook that runs once right after it is taken - after resolveAdvance. */
    private static final class RestoringLock extends ContestScoreboardApplyLock {
        private Runnable onNextLock;

        void restoreOnNextLock(Runnable action) {
            this.onNextLock = action;
        }

        @Override
        public <T> T withLock(Supplier<T> work) {
            return super.withLock(() -> {
                Runnable action = onNextLock;
                onNextLock = null;
                if (action != null) {
                    action.run();
                }
                return work.get();
            });
        }
    }
}
