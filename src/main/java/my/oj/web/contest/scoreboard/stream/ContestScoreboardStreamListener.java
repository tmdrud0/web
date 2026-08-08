package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.BatchMessageListener;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
@Slf4j
class ContestScoreboardStreamListener implements BatchMessageListener {

    private static final String STREAM_OFFSET_HEADER = "x-stream-offset";

    private final MessageConverter messageConverter;
    private final ContestScoreboardStreamProcessor processor;
    private final ContestScoreboardStreamMetrics metrics;
    private final long retryBackoffNanos;
    private final AtomicLong highestAppliedOffset = new AtomicLong(-1L);

    ContestScoreboardStreamListener(
            @Qualifier("contestJudgeMessageConverter") MessageConverter messageConverter,
            ContestScoreboardStreamProcessor processor,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardStreamConsumerProperties properties
    ) {
        this.messageConverter = messageConverter;
        this.processor = processor;
        this.metrics = metrics;
        this.retryBackoffNanos = Math.max(1L, properties.retryBackoff().toNanos());
    }

    @Override
    public void onMessageBatch(List<Message> messages) {
        try {
            List<ContestScoreboardStreamEvent> events = decode(messages);
            if (events.isEmpty()) {
                return;
            }
            metrics.recordBatchStarted(events.stream()
                    .map(event -> event.message().judgedAt())
                    .min(java.time.LocalDateTime::compareTo)
                    .orElse(null));
            highestAppliedOffset.set(processor.process(events));
        } catch (RuntimeException failure) {
            metrics.recordFailure();
            log.error("Scoreboard stream batch failed and will remain at the head for retry", failure);
            LockSupport.parkNanos(retryBackoffNanos);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
            }
            throw new ImmediateRequeueAmqpException("Retry scoreboard stream batch", failure);
        }
    }

    long highestAppliedOffset() {
        return highestAppliedOffset.get();
    }

    void initializeOffset(long offset) {
        highestAppliedOffset.set(offset);
    }

    private List<ContestScoreboardStreamEvent> decode(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ContestScoreboardStreamEvent> events = new ArrayList<>(messages.size());
        long previousOffset = -1L;
        for (Message message : messages) {
            long offset = streamOffset(message);
            if (previousOffset >= 0L && offset <= previousOffset) {
                throw new IllegalArgumentException("Scoreboard stream batch offsets are not increasing");
            }
            Object converted = messageConverter.fromMessage(message);
            if (!(converted instanceof ContestJudgeResultStreamMessage payload)) {
                throw new IllegalArgumentException("Unexpected scoreboard stream payload type");
            }
            validate(payload);
            events.add(new ContestScoreboardStreamEvent(offset, payload));
            previousOffset = offset;
        }
        return List.copyOf(events);
    }

    private static long streamOffset(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(STREAM_OFFSET_HEADER);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("RabbitMQ stream delivery has no x-stream-offset header");
    }

    private static void validate(ContestJudgeResultStreamMessage payload) {
        if (payload.schemaVersion() != ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION
                || payload.submissionId() == null
                || payload.contestId() == null
                || payload.problemId() == null
                || payload.userId() == null
                || payload.contestStart() == null
                || payload.submittedTime() == null
                || payload.judgedAt() == null
                || payload.result() == null) {
            throw new IllegalArgumentException("Invalid scoreboard stream schema or required field");
        }
    }
}
