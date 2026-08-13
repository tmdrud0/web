package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultCommand;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class RabbitContestSubmissionJudgeResultStreamPublisherTests {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @Test
    void publishesVersionedFullResultPayloadAndWaitsForAck() {
        CapturedPublish captured = ackPublish();
        RabbitContestSubmissionJudgeResultStreamPublisher publisher = publisher(Duration.ofSeconds(1));
        ContestSubmissionJudgeResultCommand command = command();

        publisher.publishAll(List.of(command));

        assertThat(captured.payload).isEqualTo(new ContestJudgeResultStreamMessage(
                1,
                command.submissionId(),
                command.contestId(),
                command.problemId(),
                command.userId(),
                command.contestStart(),
                command.submittedTime(),
                command.judgedAt(),
                command.result()
        ));
        Message message = captured.postProcessor.postProcessMessage(new Message(new byte[0]));
        assertThat(message.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void propagatesPublisherNack() {
        completePublish(new CorrelationData.Confirm(false, "disk alarm"), null);

        assertThatThrownBy(() -> publisher(Duration.ofSeconds(1)).publishAll(List.of(command())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher nack")
                .hasMessageContaining("disk alarm");
    }

    @Test
    void sendsWholeBatchBeforeWaitingForConfirms() {
        List<CorrelationData> correlations = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlations.add(correlationData);
            if (correlations.size() == 2) {
                correlations.forEach(correlation -> correlation.getFuture()
                        .complete(new CorrelationData.Confirm(true, null)));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(ContestJudgeRabbitTopology.EXCHANGE),
                eq(ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        publisher(Duration.ofSeconds(1)).publishAll(List.of(
                command(),
                new ContestSubmissionJudgeResultCommand(
                        92L,
                        10L,
                        20L,
                        30L,
                        LocalDateTime.now().minusHours(2),
                        LocalDateTime.now().minusMinutes(1),
                        SubmissionResult.WRONG_ANSWER,
                        LocalDateTime.now()
                )
        ));

        assertThat(correlations).hasSize(2);
    }

    @Test
    void propagatesMandatoryReturnEvenWhenExchangeAcked() {
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0]),
                312,
                "NO_ROUTE",
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY
        );
        completePublish(new CorrelationData.Confirm(true, null), returned);

        assertThatThrownBy(() -> publisher(Duration.ofSeconds(1)).publishAll(List.of(command())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mandatory return")
                .hasMessageContaining("NO_ROUTE");
    }

    @Test
    void propagatesConfirmTimeout() {
        doAnswer(invocation -> null).when(rabbitTemplate).convertAndSend(
                eq(ContestJudgeRabbitTopology.EXCHANGE),
                eq(ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertThatThrownBy(() -> publisher(Duration.ofMillis(1)).publishAll(List.of(command())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to confirm judge result");
    }

    private CapturedPublish ackPublish() {
        CapturedPublish captured = new CapturedPublish();
        doAnswer(invocation -> {
            captured.payload = invocation.getArgument(2);
            captured.postProcessor = invocation.getArgument(3);
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(ContestJudgeRabbitTopology.EXCHANGE),
                eq(ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
        return captured;
    }

    private void completePublish(CorrelationData.Confirm confirm, ReturnedMessage returned) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.setReturned(returned);
            correlationData.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(ContestJudgeRabbitTopology.EXCHANGE),
                eq(ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
    }

    private RabbitContestSubmissionJudgeResultStreamPublisher publisher(Duration confirmTimeout) {
        return new RabbitContestSubmissionJudgeResultStreamPublisher(
                rabbitTemplate,
                new ContestJudgeResultStreamPublisherProperties(confirmTimeout)
        );
    }

    private static ContestSubmissionJudgeResultCommand command() {
        LocalDateTime judgedAt = LocalDateTime.of(2026, 8, 8, 12, 5, 6);
        return new ContestSubmissionJudgeResultCommand(
                91L,
                10L,
                20L,
                30L,
                judgedAt.minusHours(2),
                judgedAt.minusMinutes(1),
                SubmissionResult.PARTIAL_ACCEPTED,
                judgedAt
        );
    }

    private static final class CapturedPublish {
        private ContestJudgeResultStreamMessage payload;
        private MessagePostProcessor postProcessor;
    }
}
