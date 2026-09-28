package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.redis.RedisScoreboardSnapshot;
import my.oj.web.contest.submission.messaging.ContestJudgeRabbitTopology;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.testsupport.ContestScoreboardTestData;
import my.oj.web.testsupport.ContestScoreboardTestData.Attempt;
import my.oj.web.testsupport.ContestScoreboardTestData.SeededContest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Redis restore that takes away several batches, against the real broker and the real batch script.
 *
 * <p>What the rewind has to do is re-read the range once and at full speed. The defect this pins made
 * every catch-up batch after the first a "rollback" of its own: the live path refused it, the supervisor
 * rewound again at wherever the re-read had got to, and the catch-up moved one batch per supervisor
 * interval, with a failed batch and a restart for each.</p>
 */
@SpringBootTest
// Its own context, closed afterwards: the position's applied watermark and the meters are JVM-scoped, and
// what this class asserts is one rewind for one restore.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.store=redis",
        "contest.scoreboard.recovery.mode=stream-offset",
        "contest.scoreboard.stream.consumer.enabled=true",
        // This context consumes the stream, so it runs the supervisor pass and is the recovery owner.
        "contest.scoreboard.recovery.owner.enabled=true",
        "contest.scoreboard.stream.consumer.batch-size=20",
        "contest.scoreboard.stream.consumer.prefetch=20",
        "contest.scoreboard.stream.consumer.receive-timeout=20ms",
        "contest.scoreboard.stream.consumer.retry-backoff=10ms",
        // Several supervisor passes fit inside the catch-up, which is what could have rewound it again.
        "contest.scoreboard.stream.consumer.offset-check-interval=200ms",
        "contest.scoreboard.stream.consumer.tail-probe-interval=1h",
        "contest.submission.judge.result-stream.publisher.enabled=true",
        "contest.submission.judge.rabbit.publisher.enabled=false",
        "contest.submission.judge.rabbit.listener.enabled=false",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest"
})
@EnabledIfSystemProperty(named = "rabbitIntegration", matches = "true")
class ContestScoreboardStreamRewindCatchUpRabbitIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 8, 9, 12, 0);
    private static final long FIRST_SUBMISSION_ID = 930_000_000_000_010_000L;
    private static final int BEFORE_SNAPSHOT = 20;
    /** Six batches of twenty at the configured batch size, and more when the broker hands over fewer. */
    private static final int TAKEN_AWAY = 120;
    private static final int AFTER_RESTORE = 1;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.getInteger("redisPort", 16379));
        emptyResultStream();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private ContestScoreboardApplier applier;
    @Autowired
    private ContestScoreboardStreamLifecycle lifecycle;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    @Qualifier("contestJudgeResultStreamRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    private SeededContest contest;
    private List<Attempt> attempts;

    @BeforeEach
    void seed() {
        lifecycle.stop();
        // Redeclared through the admin rather than a raw channel, so the queue is bound to the exchange
        // again: a raw delete takes the binding with it and every publication below would come back
        // unroutable.
        amqpAdmin.deleteQueue(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
        amqpAdmin.initialize();
        assertThat(amqpAdmin.getQueueProperties(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE)).isNotNull();
        contest = ContestScoreboardTestData.seedContest(jdbcTemplate, "stream-rewind-catch-up", CONTEST_START, 1, 1);
        attempts = new ArrayList<>();
        for (int i = 0; i < BEFORE_SNAPSHOT + TAKEN_AWAY + AFTER_RESTORE; i++) {
            attempts.add(new Attempt(FIRST_SUBMISSION_ID + i, contest.problemIds().get(0),
                    contest.userIds().get(0), 2, 3, SubmissionResult.WRONG_ANSWER));
        }
        ContestScoreboardTestData.insertAttempts(jdbcTemplate, contest.contestId(), CONTEST_START, attempts, true);
        ContestScoreboardTestData.flushRedis(redisTemplate);
        lifecycle.start();
    }

    @AfterEach
    void cleanUp() {
        lifecycle.stop();
        ContestScoreboardTestData.deleteContest(jdbcTemplate, contest.contestId());
        ContestScoreboardTestData.flushRedis(redisTemplate);
        // The stream is durable and shared with the classes that run after this one.
        amqpAdmin.deleteQueue(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
        amqpAdmin.initialize();
    }

    @Test
    void aRestoreSpanningSeveralBatchesIsRewoundOnceAndCaughtUpWithoutAFailedBatch() {
        publish(0, BEFORE_SNAPSHOT);
        await("the first results to be applied", () -> processedRows() == BEFORE_SNAPSHOT);
        long snapshotCheckpoint = applier.currentStreamOffset();
        RedisScoreboardSnapshot snapshot = RedisScoreboardSnapshot.take(redisTemplate);
        publish(BEFORE_SNAPSHOT, TAKEN_AWAY);
        await("the range the restore takes away to be applied",
                () -> processedRows() == BEFORE_SNAPSHOT + TAKEN_AWAY);
        long tip = applier.currentStreamOffset();
        double restartsBefore = counter("contest.scoreboard.stream.rollback.restarts");
        double failuresBefore = counter("contest.scoreboard.stream.failures");
        double failureRestartsBefore = counter("contest.scoreboard.stream.failure.restarts");

        snapshot.restoreInto(redisTemplate);
        assertThat(applier.currentStreamOffset()).isEqualTo(snapshotCheckpoint);
        assertThat(processedRows()).isEqualTo(BEFORE_SNAPSHOT);
        publish(BEFORE_SNAPSHOT + TAKEN_AWAY, AFTER_RESTORE);

        await("the catch-up to reach the tail", () -> processedRows() == attempts.size()
                && applier.currentStreamOffset() > tip);
        assertThat(counter("contest.scoreboard.stream.rollback.restarts") - restartsBefore)
                .as("one rewind for one restore")
                .isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.failures") - failuresBefore)
                .as("no catch-up batch was refused")
                .isZero();
        assertThat(counter("contest.scoreboard.stream.failure.restarts") - failureRestartsBefore).isZero();
    }

    private void publish(int from, int count) {
        for (Attempt attempt : attempts.subList(from, from + count)) {
            publishAndConfirm(message(attempt));
        }
    }

    private void publishAndConfirm(ContestJudgeResultStreamMessage message) {
        CorrelationData correlationData = new CorrelationData();
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY,
                message,
                correlationData
        );
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(10L, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker nacked scoreboard delivery: " + confirm.getReason());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing a scoreboard delivery", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException("Failed to publish a scoreboard delivery", failed);
        }
    }

    private ContestJudgeResultStreamMessage message(Attempt attempt) {
        return new ContestJudgeResultStreamMessage(
                ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                attempt.submissionId(),
                contest.contestId(),
                attempt.problemId(),
                attempt.userId(),
                CONTEST_START,
                CONTEST_START.plusMinutes(attempt.submittedMinute()),
                CONTEST_START.plusMinutes(attempt.judgedMinute()),
                attempt.result()
        );
    }

    private long processedRows() {
        Long size = redisTemplate.opsForSet().size("contest:scoreboard:" + contest.contestId() + ":processed");
        return size == null ? 0L : size;
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    /** Deletes the result stream and declares it empty again; the arguments match the topology bean's. */
    private static void emptyResultStream() {
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        try (com.rabbitmq.client.Connection connection = factory.newConnection();
             com.rabbitmq.client.Channel channel = connection.createChannel()) {
            channel.queueDelete(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
            channel.queueDeclare(
                    ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE,
                    true,
                    false,
                    false,
                    Map.of(
                            "x-queue-type", "stream",
                            "x-max-age", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_AGE,
                            "x-max-length-bytes", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_LENGTH_BYTES
                    )
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Could not empty the result stream queue", failure);
        }
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + description, interrupted);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }
}
