package my.oj.web.contest.submission.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the broker does when a consumer rejects a delivery from a stream queue.
 *
 * <p>The scoreboard consumer answers a failed batch by throwing
 * {@code ImmediateRequeueAmqpException} with {@code defaultRequeueRejected} on, which is a
 * {@code basic.reject} with {@code requeue=true}. Stream queues are not classic queues and do not
 * implement the whole rejection API, so "the batch stays at the head for retry" is a claim about
 * broker behaviour that either holds against a real broker or does not.</p>
 *
 * <p>The queue here is the test's own, so the answer is measured without leaving anything in the
 * stream the application actually consumes.</p>
 */
@EnabledIfSystemProperty(named = "rabbitIntegration", matches = "true")
class StreamQueueRequeueRabbitIntegrationTests {

    @Test
    void aRequeueingRejectionOnAStreamQueueIsAnsweredByTheBroker() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        String queue = "contest.judge.stream.requeue-probe." + UUID.randomUUID();
        AtomicReference<String> brokerAnswer = new AtomicReference<>("the channel stayed open");
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            // A stream queue is durable, non-exclusive and never auto-deleting; the broker refuses the
            // declaration outright if any of the three is set otherwise.
            channel.queueDeclare(queue, true, false, false, Map.of("x-queue-type", "stream"));
            channel.basicPublish("", queue, null, "probe".getBytes(StandardCharsets.UTF_8));

            AtomicReference<Delivery> received = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(1);
            CountDownLatch redelivered = new CountDownLatch(1);
            AtomicInteger deliveries = new AtomicInteger();
            // A stream consumer must declare a prefetch count; the broker refuses the consume otherwise.
            // This is why the application's container, which does set one, can consume a stream at all.
            channel.basicQos(10);
            channel.basicConsume(queue, false, Map.of("x-stream-offset", "first"),
                    (tag, delivery) -> {
                        received.set(delivery);
                        delivered.countDown();
                        // Only the second delivery counts down the redelivery latch; the first one is
                        // already in hand by then and would otherwise make this report itself.
                        if (deliveries.incrementAndGet() > 1) {
                            redelivered.countDown();
                        }
                    },
                    tag -> {
                    });
            assertThat(delivered.await(10, TimeUnit.SECONDS))
                    .as("the probe queue delivered its one message")
                    .isTrue();

            channel.addShutdownListener(cause -> brokerAnswer.set(cause.toString()));
            channel.basicReject(received.get().getEnvelope().getDeliveryTag(), true);
            // Rejection is asynchronous: the broker's answer arrives on the channel's next round trip.
            try {
                channel.queueDeclarePassive(queue);
            } catch (Exception failure) {
                brokerAnswer.set(failure.toString());
            }

            // Both halves are asserted, not just printed, because the application's failure handling
            // is built on them. A broker that starts killing the channel would end the consumer rather
            // than leave it stalled; a broker that starts redelivering would make the supervisor's
            // resubscribe redundant. Either change should surface here rather than silently invalidate
            // the reasoning in ContestScoreboardStreamLifecycle and ContestScoreboardStreamListener.
            assertThat(channel.isOpen())
                    .as("a requeueing rejection on a stream queue is not a connection-level error "
                            + "(broker answered: %s)", brokerAnswer.get())
                    .isTrue();
            assertThat(connection.isOpen()).isTrue();

            boolean cameBack = redelivered.await(5, TimeUnit.SECONDS);
            System.out.println("STREAM-REQUEUE-PROBE: " + brokerAnswer.get()
                    + " | channelOpen=" + channel.isOpen()
                    + " | connectionOpen=" + connection.isOpen()
                    + " | deliveries=" + deliveries.get());
            assertThat(cameBack)
                    .as("the rejected message was not handed back to the running consumer in %s "
                            + "delivery/ies, which is why the retry is driven by resubscribing at the "
                            + "stored checkpoint instead of by requeue", deliveries.get())
                    .isFalse();
        } finally {
            deleteQuietly(factory, queue);
        }
    }

    private static void deleteQuietly(ConnectionFactory factory, String queue) {
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.queueDelete(queue);
        } catch (Exception ignored) {
            // A probe that already broke its own connection cannot clean up after itself; the queue is
            // exclusive to this test and auto-expires with the broker.
        }
    }
}
