package my.oj.web.contest.submission.messaging;

import com.rabbitmq.client.GetResponse;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.testsupport.ContestScoreboardTestData;
import my.oj.web.testsupport.ContestScoreboardTestData.Attempt;
import my.oj.web.testsupport.ContestScoreboardTestData.SeededContest;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.scoreboard.stream.consumer.enabled=false",
        "contest.submission.judge.result-stream.publisher.enabled=true",
        "contest.submission.judge.rabbit.listener.enabled=true",
        "contest.submission.judge.rabbit.publisher.enabled=false",
        "contest.submission.judge.result-writer.batch-size=1",
        "contest.submission.judge.result-writer.max-wait=1ms",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest",
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1",
        "spring.rabbitmq.listener.simple.prefetch=1"
})
@EnabledIfSystemProperty(named = "rabbitIntegration", matches = "true")
class ContestJudgeStoredResultRedeliveryRabbitIntegrationTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 8, 9, 12, 0);
    private static final long SUBMISSION_ID = 940_000_000_000_000_001L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @MockitoBean
    private ContestSubmissionJudgement judgement;

    private SeededContest contest;

    @BeforeEach
    void seedStoredResult() {
        listenerRegistry.stop();
        contest = ContestScoreboardTestData.seedContest(
                jdbcTemplate, "stored-redelivery", CONTEST_START, 1, 1);
        ContestScoreboardTestData.insertAttempts(
                jdbcTemplate,
                contest.contestId(),
                CONTEST_START,
                List.of(new Attempt(
                        SUBMISSION_ID,
                        contest.problemIds().get(0),
                        contest.userIds().get(0),
                        5,
                        6,
                        SubmissionResult.ACCEPTED
                )),
                true
        );
    }

    @AfterEach
    void cleanUp() {
        listenerRegistry.stop();
        ContestScoreboardTestData.deleteContest(jdbcTemplate, contest.contestId());
    }

    @Test
    void forcedConsumerConnectionLossRepublishesStoredResultWithoutCallingJudgeAgain() throws Exception {
        long initialStreamMessages = messageCount(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.LIVE_ROUTING_KEY,
                new ContestJudgeMessage(1L, SUBMISSION_ID, 1)
        );
        await("judge message routing", () -> messageCount(ContestJudgeRabbitTopology.LIVE_QUEUE) == 1L);

        com.rabbitmq.client.ConnectionFactory forcedConsumerFactory = new com.rabbitmq.client.ConnectionFactory();
        forcedConsumerFactory.setHost("localhost");
        forcedConsumerFactory.setPort(5672);
        forcedConsumerFactory.setUsername("guest");
        forcedConsumerFactory.setPassword("guest");
        com.rabbitmq.client.Connection forcedConsumer = forcedConsumerFactory.newConnection();
        com.rabbitmq.client.Channel forcedConsumerChannel = forcedConsumer.createChannel();
        GetResponse firstDelivery = forcedConsumerChannel.basicGet(
                ContestJudgeRabbitTopology.LIVE_QUEUE,
                false
        );
        assertThat(firstDelivery).isNotNull();
        assertThat(firstDelivery.getEnvelope().isRedeliver()).isFalse();
        // Equivalent broker boundary to killing a judge process: the connection disappears with
        // one unacknowledged delivery, so RabbitMQ returns that delivery to the queue.
        forcedConsumer.abort();

        listenerRegistry.start();

        await("stored result redelivery", () -> messageCount(ContestJudgeRabbitTopology.LIVE_QUEUE) == 0L
                && messageCount(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE) == initialStreamMessages + 1L);
        verifyNoInteractions(judgement);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_result WHERE submission_id = ?",
                Long.class,
                SUBMISSION_ID
        )).isEqualTo(1L);
    }

    private long messageCount(String queue) {
        Long count = rabbitTemplate.execute(channel -> (long) channel.queueDeclarePassive(queue).getMessageCount());
        return count == null ? 0L : count;
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
