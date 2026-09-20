package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the listener leaves behind when a delivery cannot be applied.
 *
 * <p>Two things, and the second is only visible when the first cannot be done. The range the delivery
 * left unapplied is recorded from the offset it carried, so the checkpoint can never move over it. When
 * the delivery carries no readable offset there is no range to describe - and then the position is at
 * least left un-verified, so the next delivery is judged against it instead of being carried forward as
 * an ordinary step.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardStreamListenerTests {

    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 8, 9, 12, 0);

    @Mock
    private MessageConverter messageConverter;
    @Mock
    private ContestScoreboardStreamProcessor processor;

    private ContestScoreboardStreamPosition position;
    private MeterRegistry registry;
    private ContestScoreboardStreamListener listener;

    @BeforeEach
    void setUp() {
        position = new ContestScoreboardStreamPosition();
        // The position a running consumer holds: verified against the checkpoint it resumed at.
        position.markAnchorVerified();
        registry = new SimpleMeterRegistry();
        listener = new ContestScoreboardStreamListener(
                messageConverter,
                processor,
                position,
                new ContestScoreboardStreamMetrics(registry),
                new ContestScoreboardStreamConsumerProperties(
                        500, 500, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096)
        );
    }

    /** A delivery that decodes into nothing appliable: the whole batch is held back from its first offset. */
    @Test
    void aBatchThatCouldNotBeAppliedIsHeldBackFromItsFirstOffset() {
        when(messageConverter.fromMessage(any()))
                .thenReturn(messageWithSchemaVersion(ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION + 1));

        assertThatThrownBy(() -> listener.onMessageBatch(List.of(message(4L))))
                .isInstanceOf(ImmediateRequeueAmqpException.class);

        assertThat(position.unappliedFrom()).isEqualTo(4L);
        assertThat(position.anchorVerified()).isFalse();
        assertThat(position.failedBatches()).isEqualTo(1L);
        assertThat(registry.get("contest.scoreboard.stream.failures").counter().count()).isEqualTo(1.0);
        verify(processor, never()).process(anyList());
    }

    /**
     * The delivery whose offsets cannot be read at all. There is no range to hold back - inventing one
     * would refuse the very re-read that recovers it - so the un-verified position is what stands, and
     * it is what makes the next delivery a question rather than an ordinary step over this one.
     */
    @Test
    void aFailedBatchWithNoReadableOffsetStillLeavesAPositionToEstablishAgain() {
        assertThatThrownBy(() -> listener.onMessageBatch(List.of(messageWithoutOffset())))
                .isInstanceOf(ImmediateRequeueAmqpException.class);

        assertThat(position.unappliedFrom()).isEqualTo(-1L);
        assertThat(position.anchorVerified()).isFalse();
        assertThat(position.failedBatches()).isEqualTo(1L);
        assertThat(registry.get("contest.scoreboard.stream.failures").counter().count()).isEqualTo(1.0);
        verify(processor, never()).process(anyList());
    }

    private static Message message(long offset) {
        MessageProperties properties = new MessageProperties();
        properties.getHeaders().put("x-stream-offset", offset);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    /** A delivery the broker handed over without the header this contract reads its position from. */
    private static Message messageWithoutOffset() {
        return new Message("{}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private static ContestJudgeResultStreamMessage messageWithSchemaVersion(int schemaVersion) {
        return new ContestJudgeResultStreamMessage(
                schemaVersion,
                940_000_000_000_000_001L,
                1L,
                1L,
                1L,
                CONTEST_START,
                CONTEST_START.plusMinutes(1),
                CONTEST_START.plusMinutes(2),
                SubmissionResult.ACCEPTED
        );
    }
}
