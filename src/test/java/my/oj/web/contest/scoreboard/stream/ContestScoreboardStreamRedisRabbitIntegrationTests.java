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
import org.springframework.amqp.core.AmqpAdmin;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
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
        "contest.scoreboard.stream.consumer.tail-probe-interval=1h",
        "contest.scoreboard.stream.consumer.tail-probe-quiet-period=20ms",
        "contest.scoreboard.stream.consumer.tail-probe-timeout=1s",
        "contest.submission.judge.result-stream.publisher.enabled=true",
        "contest.submission.judge.rabbit.publisher.enabled=false",
        "contest.submission.judge.rabbit.listener.enabled=false",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest"
})
@EnabledIfSystemProperty(named = "rabbitIntegration", matches = "true")
class ContestScoreboardStreamRedisRabbitIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 8, 9, 12, 0);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> Integer.getInteger("redisPort", 16379));
        emptyResultStream();
    }

    /**
     * Empties the result stream before the context exists, and this has to happen here rather than in
     * a test method.
     *
     * <p>The queue is durable and outlives the JVM, so a previous run's messages are still in it when
     * the context comes up. The tail monitor probes once at startup - before any test can reset
     * anything - and records the greatest offset it sees as the stream tail. That record is a running
     * maximum, so a tail inherited from an earlier run stays the reported tail for the life of the
     * context and {@code contest.scoreboard.pending} reads the difference against it forever. Starting
     * from an empty stream is what makes the offsets this test names the offsets the broker issues.</p>
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
            // Declared straight away so the startup probe cannot race the application's own
            // declaration and find no queue at all. The arguments match the topology bean's.
            channel.queueDeclare(
                    ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE,
                    true,
                    false,
                    false,
                    java.util.Map.of(
                            "x-queue-type", "stream",
                            "x-max-age", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_AGE,
                            "x-max-length-bytes", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_LENGTH_BYTES
                    )
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Could not empty the result stream before the context starts", failure);
        }
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
    private ContestScoreboardStreamTailOffsetMonitor tailOffsetMonitor;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    @Qualifier("contestJudgeResultStreamRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    private SeededContest contest;
    private List<Attempt> attempts;
    /**
     * The applied counter at the point the queue was recreated. The consumer is a lifecycle bean, so
     * it is already consuming when the context comes up - before this class can empty the queue - and
     * whatever a previous run left in the stream is applied and counted then. The assertions below are
     * about what this test applies, so they read the growth rather than the total.
     */
    private double appliedBaseline;

    @BeforeEach
    void seed() {
        lifecycle.stop();
        // The result stream is durable and the broker is not restarted between runs, so anything an
        // earlier run published is still in it and the offsets named below would start where that run
        // left off. Recreating the queue is what makes this test repeatable; the assertion confirms the
        // redeclaration took, so a broker that refuses it fails here rather than as a timeout later.
        amqpAdmin.deleteQueue(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
        amqpAdmin.initialize();
        assertThat(amqpAdmin.getQueueProperties(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE))
                .as("the result stream queue was redeclared at offset zero")
                .isNotNull();
        ContestScoreboardTestData.flushRedis(redisTemplate);
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "stream-replay", CONTEST_START, 1, 1);
        attempts = List.of(
                new Attempt(930_000_000_000_000_001L, contest.problemIds().get(0),
                        contest.userIds().get(0), 2, 3, SubmissionResult.WRONG_ANSWER),
                new Attempt(930_000_000_000_000_002L, contest.problemIds().get(0),
                        contest.userIds().get(0), 10, 11, SubmissionResult.ACCEPTED),
                new Attempt(930_000_000_000_000_003L, contest.problemIds().get(0),
                        contest.userIds().get(0), 12, 13, SubmissionResult.WRONG_ANSWER)
        );
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate, contest.contestId(), CONTEST_START, attempts, true);
        appliedBaseline = appliedCount();
        lifecycle.start();
    }

    @AfterEach
    void cleanUp() {
        lifecycle.stop();
        ContestScoreboardTestData.deleteContest(jdbcTemplate, contest.contestId());
        ContestScoreboardTestData.flushRedis(redisTemplate);
    }

    @Test
    void consumesWithOffsetMetricsAndRestoresAfterRedisIsEmptied() throws Exception {
        for (Attempt attempt : attempts.subList(0, 2)) {
            rabbitTemplate.convertAndSend(
                    ContestJudgeRabbitTopology.EXCHANGE,
                    ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY,
                    message(attempt)
            );
        }

        await("initial stream application", () -> applier.currentStreamOffset() == 1L
                && appliedRows() == 2L
                && processedRows() == 2L);
        assertThat(appliedCount() - appliedBaseline).isEqualTo(2.0);
        tailOffsetMonitor.observeTailOffset();
        assertThat(pendingEvents()).isZero();
        assertDetailedQueueMetricsIncludeStream();

        lifecycle.stop();
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY,
                message(attempts.get(2))
        );
        tailOffsetMonitor.observeTailOffset();
        assertThat(pendingEvents()).isEqualTo(1.0);
        assertThat(appliedRows()).isEqualTo(2L);

        lifecycle.start();
        await("pending stream event application", () -> applier.currentStreamOffset() == 2L
                && appliedRows() == 3L
                && processedRows() == 3L);
        assertThat(pendingEvents()).isZero();

        lifecycle.stop();
        ContestScoreboardTestData.flushRedis(redisTemplate);
        assertThat(applier.currentStreamOffset()).isEqualTo(-1L);

        lifecycle.start();

        await("replay after Redis loss", () -> applier.currentStreamOffset() == 2L
                && processedRows() == 3L);
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "solved")).isEqualTo("1");
        assertThat(redisTemplate.opsForHash().get(summaryKey(), "penalty")).isEqualTo("15");
        assertThat(appliedRows()).isEqualTo(3L);
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

    private long appliedRows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = ? AND scoreboard_applied_at IS NOT NULL",
                Long.class,
                contest.contestId()
        );
        return count == null ? 0L : count;
    }

    private long processedRows() {
        Long count = redisTemplate.opsForSet().size(
                "contest:scoreboard:" + contest.contestId() + ":processed");
        return count == null ? 0L : count;
    }

    private String summaryKey() {
        return "contest:scoreboard:" + contest.contestId()
                + ":user:" + contest.userIds().get(0) + ":summary";
    }

    private double pendingEvents() {
        return meterRegistry.get("contest.scoreboard.pending").gauge().value();
    }

    private double appliedCount() {
        return meterRegistry.get("contest.scoreboard.applied").counter().count();
    }

    private static void assertDetailedQueueMetricsIncludeStream() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI metrics = URI.create(
                "http://localhost:15692/metrics/detailed?vhost=%2F&family=queue_coarse_metrics");
        final String[] body = {""};
        await("RabbitMQ detailed queue metric for the result stream", () -> {
            try {
                body[0] = client.send(
                        HttpRequest.newBuilder(metrics).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                ).body();
                return body[0].contains("rabbitmq_detailed_queue_messages")
                        && body[0].contains(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
            } catch (Exception ignored) {
                return false;
            }
        });
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
