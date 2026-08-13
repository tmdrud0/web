package my.oj.web.contest.scoreboard.stream;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.messaging.ContestJudgeRabbitTopology;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Observes the stream tail without enabling RabbitMQ's native Stream protocol.
 *
 * <p>AMQP 0.9.1 exposes each delivered message's {@code x-stream-offset}, but it does not expose
 * broker-maintained consumer offset lag. A short-lived consumer starting at {@code last} therefore
 * drains the final storage chunk, remembers its greatest offset, and is cancelled after a quiet
 * period. The probe never changes the scoreboard checkpoint: the only authoritative applied
 * offset remains the value written by the scoreboard Lua script.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
@Slf4j
class ContestScoreboardStreamTailOffsetMonitor {

    private static final long NO_OFFSET = Long.MIN_VALUE;

    private final ConnectionFactory connectionFactory;
    private final ContestScoreboardStreamConsumerProperties properties;
    private final ContestScoreboardStreamMetrics metrics;

    ContestScoreboardStreamTailOffsetMonitor(
            ConnectionFactory connectionFactory,
            ContestScoreboardStreamConsumerProperties properties,
            ContestScoreboardStreamMetrics metrics
    ) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${contest.scoreboard.stream.consumer.tail-probe-interval:5s}")
    void observeTailOffset() {
        Channel channel = null;
        String consumerTag = null;
        try {
            Connection connection = connectionFactory.createConnection();
            channel = connection.createChannel(false);
            boolean streamHasMessages = channel
                    .queueDeclarePassive(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE)
                    .getMessageCount() > 0;
            channel.basicQos(properties.effectiveTailProbePrefetch());

            AtomicLong greatestOffset = new AtomicLong(NO_OFFSET);
            AtomicLong lastDeliveryNanos = new AtomicLong();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            Channel consumerChannel = channel;

            consumerTag = channel.basicConsume(
                    ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE,
                    false,
                    Map.of("x-stream-offset", "last"),
                    (tag, delivery) -> {
                        try {
                            long offset = streamOffset(delivery.getProperties().getHeaders().get("x-stream-offset"));
                            greatestOffset.accumulateAndGet(offset, Math::max);
                            lastDeliveryNanos.set(System.nanoTime());
                            synchronized (consumerChannel) {
                                consumerChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            }
                        } catch (Throwable failure) {
                            callbackFailure.compareAndSet(null, failure);
                        }
                    },
                    tag -> {
                    }
            );

            awaitTailQuietPeriod(greatestOffset, lastDeliveryNanos, callbackFailure);
            Throwable failure = callbackFailure.get();
            if (failure != null) {
                throw new IllegalStateException("Stream tail probe callback failed", failure);
            }
            long observedOffset = greatestOffset.get();
            if (observedOffset != NO_OFFSET) {
                metrics.recordLatestOffset(observedOffset);
            } else if (streamHasMessages) {
                throw new IllegalStateException("Timed out before the non-empty stream delivered its tail");
            }
        } catch (RuntimeException | IOException failure) {
            metrics.recordTailProbeFailure();
            log.warn("Could not observe the scoreboard result stream tail", failure);
        } finally {
            cancelAndClose(channel, consumerTag);
        }
    }

    private void awaitTailQuietPeriod(
            AtomicLong greatestOffset,
            AtomicLong lastDeliveryNanos,
            AtomicReference<Throwable> callbackFailure
    ) {
        long started = System.nanoTime();
        long timeoutNanos = positiveNanos(properties.tailProbeTimeout());
        long quietNanos = positiveNanos(properties.tailProbeQuietPeriod());
        while (System.nanoTime() - started < timeoutNanos) {
            if (callbackFailure.get() != null) {
                return;
            }
            long lastDelivery = lastDeliveryNanos.get();
            if (greatestOffset.get() != NO_OFFSET
                    && lastDelivery != 0L
                    && System.nanoTime() - lastDelivery >= quietNanos) {
                return;
            }
            try {
                Thread.sleep(Math.min(10L, Math.max(1L, properties.tailProbeQuietPeriod().toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the stream tail", interrupted);
            }
        }
    }

    private static long positiveNanos(Duration duration) {
        return Math.max(1L, duration.toNanos());
    }

    private static long streamOffset(Object rawOffset) {
        if (rawOffset instanceof Number number) {
            return number.longValue();
        }
        if (rawOffset != null) {
            return Long.parseLong(rawOffset.toString());
        }
        throw new IllegalArgumentException("Stream delivery is missing x-stream-offset");
    }

    private static void cancelAndClose(Channel channel, String consumerTag) {
        if (channel == null) {
            return;
        }
        try {
            synchronized (channel) {
                if (channel.isOpen() && consumerTag != null) {
                    channel.basicCancel(consumerTag);
                }
                if (channel.isOpen()) {
                    channel.close();
                }
            }
        } catch (IOException | TimeoutException closeFailure) {
            log.debug("Could not close scoreboard stream tail probe channel", closeFailure);
        }
    }
}
