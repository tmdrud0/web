package my.oj.web.contest.scoreboard.stream;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import my.oj.web.contest.submission.messaging.ContestJudgeRabbitTopology;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The premise the stream tests that assemble a batch out of several deliveries rest on: the order they
 * publish in is the order the broker hands back offsets in.
 *
 * <p>It is not free. A publish hands its frame to the connection's writer and returns, so several
 * publications in a row reach the broker without any of them waiting for the one before it, and the
 * broker assigns offsets in the order it receives them. A run of five on this broker came back as
 * offset 0 for the first publication, offset 1 for the fifth, and 2, 3 and 4 for the second, third and
 * fourth. A batch arranged that way is not the arrangement the test intended, and the failure it
 * produces looks like a scoreboard defect while being nothing of the sort.</p>
 *
 * <p>Waiting for each publication's broker confirm restores the order: the confirm for a stream queue
 * is sent once the message is in the log, so the next publication cannot be given a lower offset. That
 * is the form the order-dependent tests use, and what this test measures.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.submission.judge.result-stream.publisher.enabled=true",
        "contest.submission.judge.rabbit.publisher.enabled=false",
        "contest.submission.judge.rabbit.listener.enabled=false",
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest"
})
@EnabledIfSystemProperty(named = "rabbitIntegration", matches = "true")
class StreamPublishConfirmOrderRabbitIntegrationTests {

    private static final Pattern SUBMISSION_ID = Pattern.compile("\"submissionId\"\\s*:\\s*(\\d+)");
    private static final LocalDateTime JUDGED_AT = LocalDateTime.of(2026, 8, 9, 12, 0);
    private static final long FIRST_ID = 930_000_000_000_000_301L;

    @Autowired
    @Qualifier("contestJudgeResultStreamRabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Test
    void aPublicationThatWaitsForItsConfirmIsGivenTheNextOffset() throws Exception {
        emptyResultStream();
        List<Long> published = List.of(FIRST_ID, FIRST_ID + 1L, FIRST_ID + 2L, FIRST_ID + 3L, FIRST_ID + 4L);
        for (long submissionId : published) {
            publishAndConfirm(submissionId);
        }

        assertThat(offsetOrderOfFirst(published.size()))
                .as("the broker handed back the offsets in the order the publications were confirmed in")
                .containsExactlyElementsOf(published);

        emptyResultStream();
    }

    private void publishAndConfirm(long submissionId) throws Exception {
        CorrelationData correlationData = new CorrelationData();
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY,
                new ContestJudgeResultStreamMessage(
                        ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                        submissionId,
                        1L,
                        1L,
                        1L,
                        JUDGED_AT,
                        JUDGED_AT,
                        JUDGED_AT,
                        SubmissionResult.ACCEPTED
                ),
                correlationData
        );
        CorrelationData.Confirm confirm = correlationData.getFuture().get(10L, TimeUnit.SECONDS);
        assertThat(confirm.isAck())
                .as("the broker acknowledged submission %s", submissionId)
                .isTrue();
    }

    /** Reads the deliveries back from the beginning of retention, in the order the stream holds them. */
    private static List<Long> offsetOrderOfFirst(int count) throws Exception {
        List<Long> order = Collections.synchronizedList(new ArrayList<>(count));
        CountDownLatch received = new CountDownLatch(count);
        try (com.rabbitmq.client.Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
            // Stream queues refuse auto-acknowledgement and require a prefetch, which is why this
            // consumer is written out rather than taken from a helper.
            channel.basicQos(count);
            String consumerTag = channel.basicConsume(
                    ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE,
                    false,
                    Map.of("x-stream-offset", "first"),
                    (tag, delivery) -> {
                        Matcher matcher = SUBMISSION_ID.matcher(
                                new String(delivery.getBody(), StandardCharsets.UTF_8));
                        if (matcher.find()) {
                            order.add(Long.parseLong(matcher.group(1)));
                        }
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                        received.countDown();
                    },
                    tag -> {
                    }
            );
            received.await(10L, TimeUnit.SECONDS);
            channel.basicCancel(consumerTag);
        }
        return order;
    }

    /**
     * Deletes the result stream and declares it empty again, so this class reads only what it published
     * and so it leaves nothing behind for the classes that run after it.
     */
    private static void emptyResultStream() throws Exception {
        try (com.rabbitmq.client.Connection connection = connectionFactory().newConnection();
             Channel channel = connection.createChannel()) {
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
        }
    }

    private static ConnectionFactory connectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        return factory;
    }
}
