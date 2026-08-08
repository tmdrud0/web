package my.oj.web.contest.submission.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.observability.ContestOutboxDrainMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestJudgeOutboxRelayTests {

    private final ContestJudgeOutboxStore outboxStore = mock(ContestJudgeOutboxStore.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ContestOutboxDrainMetrics drainMetrics = new ContestOutboxDrainMetrics();
    private final ContestJudgeOutboxRelay relay = new ContestJudgeOutboxRelay(
            outboxStore,
            rabbitTemplate,
            new ContestJudgeOutboxRelayProperties(
                    50,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(10)
            ),
            drainMetrics
    );
    private final ContestJudgeOutboxStore.ClaimedEvent event =
            new ContestJudgeOutboxStore.ClaimedEvent(7L, 42L, "claim-token");

    @BeforeEach
    void setUp() {
        drainMetrics.bindTo(registry);
        when(outboxStore.completeAll(anyList(), anyList()))
                .thenReturn(new ContestJudgeOutboxStore.BatchCompletionResult(0, 0, 0, 0));
    }

    /**
     * A completion the store rejected as stale changed no row, so the event is still someone
     * else's to publish. Counting the requested updates instead would let the drain rate outrun
     * the work and understate the estimated drain time built on it.
     */
    @Test
    void countsAppliedUpdatesRatherThanRequestedOnes() {
        when(outboxStore.claim(any(Integer.class), any())).thenReturn(List.of());
        when(outboxStore.completeAll(anyList(), anyList()))
                .thenReturn(new ContestJudgeOutboxStore.BatchCompletionResult(9, 7, 4, 2));

        relay.relay();

        assertThat(counter("contest.outbox.drained")).isEqualTo(7.0);
        assertThat(counter("contest.outbox.retries")).isEqualTo(2.0);
        assertThat(registry.get("contest.scoreboard.applied").counter().count()).isZero();
    }

    private double counter(String name) {
        return registry.get(name).tag("outbox", ContestOutboxDrainMetrics.JUDGE_OUTBOX).counter().count();
    }

    @Test
    void marksPublishedOnlyAfterPositiveConfirm() {
        when(outboxStore.claim(any(Integer.class), any())).thenReturn(List.of(event));
        completePublishWith(new CorrelationData.Confirm(true, null), null);

        relay.relay();

        verify(outboxStore).completeAll(List.of(event), List.of());
    }

    @Test
    void restoresPendingOnPublisherNack() {
        when(outboxStore.claim(any(Integer.class), any())).thenReturn(List.of(event));
        completePublishWith(new CorrelationData.Confirm(false, "broker unavailable"), null);

        relay.relay();

        verify(outboxStore).completeAll(
                List.of(),
                List.of(new ContestJudgeOutboxStore.FailedEvent(
                        event,
                        "Rabbit publisher nack: broker unavailable"
                ))
        );
    }

    @Test
    void restoresPendingOnMandatoryReturn() {
        when(outboxStore.claim(any(Integer.class), any())).thenReturn(List.of(event));
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                ContestJudgeRabbitTopology.EXCHANGE,
                ContestJudgeRabbitTopology.LIVE_ROUTING_KEY
        );
        completePublishWith(new CorrelationData.Confirm(true, null), returned);

        relay.relay();

        verify(outboxStore).completeAll(
                List.of(),
                List.of(new ContestJudgeOutboxStore.FailedEvent(event, "Rabbit mandatory return: NO_ROUTE"))
        );
    }

    @Test
    void restoresPendingWhenSendThrows() {
        when(outboxStore.claim(any(Integer.class), any())).thenReturn(List.of(event));
        doThrow(new IllegalStateException("connection closed"))
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(ContestJudgeRabbitTopology.EXCHANGE),
                        eq(ContestJudgeRabbitTopology.LIVE_ROUTING_KEY),
                        any(ContestJudgeMessage.class),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class)
                );

        relay.relay();

        verify(outboxStore).completeAll(
                List.of(),
                List.of(new ContestJudgeOutboxStore.FailedEvent(
                        event,
                        "IllegalStateException: connection closed"
                ))
        );
    }

    private void completePublishWith(CorrelationData.Confirm confirm, ReturnedMessage returned) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            if (returned != null) {
                correlationData.setReturned(returned);
            }
            correlationData.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(ContestJudgeRabbitTopology.EXCHANGE),
                eq(ContestJudgeRabbitTopology.LIVE_ROUTING_KEY),
                any(ContestJudgeMessage.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
    }
}
