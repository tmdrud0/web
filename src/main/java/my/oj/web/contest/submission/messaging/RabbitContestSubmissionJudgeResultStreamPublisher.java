package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultCommand;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultStreamPublisher;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        prefix = "contest.submission.judge.result-stream.publisher",
        name = "enabled",
        havingValue = "true"
)
class RabbitContestSubmissionJudgeResultStreamPublisher
        implements ContestSubmissionJudgeResultStreamPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    RabbitContestSubmissionJudgeResultStreamPublisher(
            @Qualifier("contestJudgeResultStreamRabbitTemplate") RabbitTemplate rabbitTemplate,
            ContestJudgeResultStreamPublisherProperties properties
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.confirmTimeout();
    }

    @Override
    public void publishAll(List<ContestSubmissionJudgeResultCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }

        List<PublishAttempt> attempts = new ArrayList<>(commands.size());
        for (ContestSubmissionJudgeResultCommand command : commands) {
            attempts.add(send(command));
        }

        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        for (PublishAttempt attempt : attempts) {
            awaitConfirm(attempt, deadline);
        }
    }

    private PublishAttempt send(ContestSubmissionJudgeResultCommand command) {
        CorrelationData correlationData = new CorrelationData();
        rabbitTemplate.convertAndSend(
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY,
                ContestJudgeResultStreamMessage.from(command),
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                correlationData
        );
        return new PublishAttempt(command.submissionId(), correlationData);
    }

    private void awaitConfirm(PublishAttempt attempt, long deadline) {
        try {
            long remainingNanos = Math.max(1L, deadline - System.nanoTime());
            CorrelationData.Confirm confirm = attempt.correlationData().getFuture()
                    .get(remainingNanos, TimeUnit.NANOSECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException(
                        "Rabbit publisher nack for judge result " + attempt.submissionId()
                                + ": " + confirm.getReason()
                );
            }
            if (attempt.correlationData().getReturned() != null) {
                throw new IllegalStateException(
                        "Rabbit mandatory return for judge result " + attempt.submissionId()
                                + ": " + attempt.correlationData().getReturned().getReplyText()
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while confirming judge result " + attempt.submissionId(),
                    exception
            );
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException(
                    "Failed to confirm judge result " + attempt.submissionId(),
                    exception
            );
        }
    }

    private record PublishAttempt(Long submissionId, CorrelationData correlationData) {
    }
}
