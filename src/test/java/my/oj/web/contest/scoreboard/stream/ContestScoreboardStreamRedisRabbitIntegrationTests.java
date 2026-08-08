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
    private ContestScoreboardStreamTailOffsetMonitor tailOffsetMonitor;
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
        assertThat(meterRegistry.get("contest.scoreboard.applied").counter().count()).isEqualTo(2.0);
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
