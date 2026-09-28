package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a failed batch does to the checkpoint, against the real broker.
 *
 * <p>The requirement is that a batch which fails part way through leaves the offset unadvanced. The
 * unit tests decide that from a mocked applier, which cannot show what the broker does with a rejected
 * delivery - and the broker's answer is the one that surprised us: a stream queue accepts a requeueing
 * rejection without complaint and never hands the message back to the running consumer
 * ({@code StreamQueueRequeueRabbitIntegrationTests} measures exactly that). So the retry cannot come
 * from requeue; it comes from resubscribing at the stored checkpoint.</p>
 *
 * <p>Which makes this the test that has to hold: the poison is never applied <em>and</em> never
 * skipped. A valid result published behind it must stay unapplied too, because a checkpoint that
 * stepped over the poison would lose that result silently - the failure mode the continuity check
 * exists to prevent.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // These tests read scoreboard_applied_at, which stream-offset no longer writes by default.
        "contest.scoreboard.stream-offset.applied-at-tracking=true",
        "contest.scoreboard.store=redis",
        "contest.scoreboard.stream.consumer.enabled=true",
        // This context consumes the stream, so it runs the supervisor pass and is the
        // recovery owner by definition. application-test.properties declares no owner, so
        // the declaration has to be made here rather than inherited.
        "contest.scoreboard.recovery.owner.enabled=true",
        "contest.scoreboard.stream.consumer.batch-size=20",
        "contest.scoreboard.stream.consumer.prefetch=20",
        "contest.scoreboard.stream.consumer.receive-timeout=20ms",
        "contest.scoreboard.stream.consumer.retry-backoff=10ms",
        // Short enough that the supervisor pass runs during the test, which is the recovery this
        // asserts on: nothing else brings a failed batch back.
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
class ContestScoreboardStreamBatchFailureRabbitIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 8, 9, 12, 0);
    private static final long POISON_SUBMISSION_ID = 930_000_000_000_000_101L;
    private static final long VALID_SUBMISSION_ID = 930_000_000_000_000_102L;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.getInteger("redisPort", 16379));
        // Same reason as the neighbouring stream test: the queue is durable and the tail monitor probes
        // once at startup, before this class can empty anything.
        emptyResultStream();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
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

    @BeforeEach
    void seed() {
        lifecycle.stop();
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "stream-batch-failure", CONTEST_START, 1, 1);
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate,
                contest.contestId(),
                CONTEST_START,
                List.of(
                        new Attempt(POISON_SUBMISSION_ID, contest.problemIds().get(0),
                                contest.userIds().get(0), 2, 3, SubmissionResult.ACCEPTED),
                        new Attempt(VALID_SUBMISSION_ID, contest.problemIds().get(0),
                                contest.userIds().get(0), 4, 5, SubmissionResult.ACCEPTED)
                ),
                true
        );
        ContestScoreboardTestData.flushRedis(redisTemplate);
        lifecycle.start();
    }

    @AfterEach
    void cleanUp() {
        lifecycle.stop();
        ContestScoreboardTestData.deleteContest(jdbcTemplate, contest.contestId());
        ContestScoreboardTestData.flushRedis(redisTemplate);
        // The poison must not outlive the test: the stream is durable and shared with the classes that
        // run after this one.
        emptyResultStream();
    }

    @Test
    void aFailedBatchLeavesTheCheckpointBehindItAndIsRetriedFromThere() {
        // Each publication waits for its broker confirm, because the offsets this test reasons about
        // are the ones the broker issues and a publication that does not wait can be given a lower
        // offset than the one before it - measured in StreamPublishConfirmOrderRabbitIntegrationTests.
        publishAndConfirm(poisonMessage());
        await("the batch to fail", () -> counter("contest.scoreboard.stream.failures") >= 1.0);
        // The valid result goes in only once the poison has failed the batch it was in, which puts the
        // consumer's position past the offset that failed. That is the arrangement this test is for: a
        // delivery above a range a failed batch left unapplied, which the broker will never hand back
        // and the checkpoint must therefore never step over.
        publishAndConfirm(validMessage());
        // The recovery the design rests on: the supervisor resubscribes at the stored checkpoint,
        // because the broker will not hand the rejected batch back on its own.
        await("the consumer to resubscribe", () -> counter("contest.scoreboard.stream.failure.restarts") >= 1.0);

        // Nothing was applied: not the poison, and not the valid result sitting behind it. A checkpoint
        // that had stepped over the poison would have applied that result and lost nothing visibly -
        // which is why it must not step over it.
        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);
        assertThat(appliedRows()).isZero();
        assertThat(processedScoreboardRows()).isZero();
        assertThat(counter("contest.scoreboard.applied")).isZero();
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

    private ContestJudgeResultStreamMessage poisonMessage() {
        return message(POISON_SUBMISSION_ID, ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION + 1);
    }

    private ContestJudgeResultStreamMessage validMessage() {
        return message(VALID_SUBMISSION_ID, ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION);
    }

    private ContestJudgeResultStreamMessage message(long submissionId, int schemaVersion) {
        return new ContestJudgeResultStreamMessage(
                schemaVersion,
                submissionId,
                contest.contestId(),
                contest.problemIds().get(0),
                contest.userIds().get(0),
                CONTEST_START,
                CONTEST_START.plusMinutes(2),
                CONTEST_START.plusMinutes(3),
                SubmissionResult.ACCEPTED
        );
    }

    private long appliedRows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = ? AND scoreboard_applied_at IS NOT NULL",
                Long.class,
                contest.contestId()
        );
        return count == null ? 0L : count;
    }

    private long processedScoreboardRows() {
        Long size = redisTemplate.opsForSet().size("contest:scoreboard:" + contest.contestId() + ":processed");
        return size == null ? 0L : size;
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    /**
     * Deletes the result stream and declares it empty again, so the offsets this class reasons about
     * are the ones the broker issues from zero - and so a poison message does not survive into the
     * next class. The arguments match the topology bean's.
     */
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
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
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
