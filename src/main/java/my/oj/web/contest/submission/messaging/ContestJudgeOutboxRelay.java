package my.oj.web.contest.submission.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "contest.submission.judge.rabbit.publisher", name = "enabled", havingValue = "true")
class ContestJudgeOutboxRelay {

    static final int SCHEMA_VERSION = 1;
    private final ContestJudgeOutboxStore outboxStore;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final Duration claimLease;
    private final Duration confirmTimeout;

    ContestJudgeOutboxRelay(ContestJudgeOutboxStore outboxStore,
                            @Qualifier("contestJudgeRabbitTemplate") RabbitTemplate rabbitTemplate,
                            ContestJudgeOutboxRelayProperties properties) {
        this.outboxStore = outboxStore;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = properties.effectiveBatchSize();
        this.claimLease = properties.claimTimeout();
        this.confirmTimeout = properties.confirmTimeout();
    }

    @Scheduled(fixedDelayString = "${contest.submission.judge.rabbit.publisher.poll-interval-ms:1000}")
    void relay() {
        List<PublishAttempt> attempts = new ArrayList<>();
        List<ContestJudgeOutboxStore.FailedEvent> failures = new ArrayList<>();
        for (ContestJudgeOutboxStore.ClaimedEvent event : outboxStore.claim(batchSize, claimLease)) {
            try {
                attempts.add(new PublishAttempt(event, send(event)));
            } catch (Exception exception) {
                failures.add(new ContestJudgeOutboxStore.FailedEvent(event, publishError(exception)));
                log.warn("Failed to send contest judge event {}", event.eventId(), exception);
            }
        }

        List<ContestJudgeOutboxStore.ClaimedEvent> published = new ArrayList<>(attempts.size());
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        for (PublishAttempt attempt : attempts) {
            String failure = awaitConfirm(attempt, deadline);
            if (failure == null) {
                published.add(attempt.event());
            } else {
                failures.add(new ContestJudgeOutboxStore.FailedEvent(attempt.event(), failure));
            }
        }

        ContestJudgeOutboxStore.BatchCompletionResult result = outboxStore.completeAll(published, failures);
        if (result.staleCount() > 0) {
            log.debug("Ignored {} stale contest judge outbox completion results", result.staleCount());
        }
    }

    private CorrelationData send(ContestJudgeOutboxStore.ClaimedEvent event) {
        CorrelationData correlationData = new CorrelationData(event.eventId() + ":" + event.claimToken());
        ContestJudgeMessage payload = new ContestJudgeMessage(
                event.eventId(), event.submissionId(), SCHEMA_VERSION
        );
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.LIVE_ROUTING_KEY,
                payload,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                correlationData
        );
        return correlationData;
    }

    private String awaitConfirm(PublishAttempt attempt, long deadline) {
        CorrelationData correlationData = attempt.correlationData();
        try {
            long remainingNanos = Math.max(1, deadline - System.nanoTime());
            CorrelationData.Confirm confirm = correlationData.getFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
            if (!confirm.isAck()) {
                return "Rabbit publisher nack: " + confirm.getReason();
            }
            if (correlationData.getReturned() != null) {
                return "Rabbit mandatory return: " + correlationData.getReturned().getReplyText();
            }
            return null;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to confirm contest judge event {}", attempt.event().eventId(), exception);
            return publishError(exception);
        }
    }

    private String publishError(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record PublishAttempt(ContestJudgeOutboxStore.ClaimedEvent event, CorrelationData correlationData) {
    }
}
