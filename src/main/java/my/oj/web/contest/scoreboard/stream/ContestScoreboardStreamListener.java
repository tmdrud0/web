package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.delivery.RabbitStreamDeliveryCondition;
import org.springframework.context.annotation.Conditional;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.BatchMessageListener;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
@Conditional(RabbitStreamDeliveryCondition.class)
@Slf4j
class ContestScoreboardStreamListener implements BatchMessageListener {

    private static final String STREAM_OFFSET_HEADER = "x-stream-offset";

    private final MessageConverter messageConverter;
    private final ContestScoreboardStreamProcessor processor;
    private final ContestScoreboardStreamPosition position;
    private final ContestScoreboardStreamMetrics metrics;
    private final long retryBackoffNanos;
    private final ContestScoreboardStreamRollbackSignal rollbackSignal;

    ContestScoreboardStreamListener(
            MessageConverter messageConverter,
            ContestScoreboardStreamProcessor processor,
            ContestScoreboardStreamPosition position,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardStreamConsumerProperties properties
    ) {
        this(messageConverter, processor, position, metrics, properties, new ContestScoreboardStreamRollbackSignal());
    }

    @Autowired
    ContestScoreboardStreamListener(
            @Qualifier("contestJudgeMessageConverter") MessageConverter messageConverter,
            ContestScoreboardStreamProcessor processor,
            ContestScoreboardStreamPosition position,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardStreamConsumerProperties properties,
            ContestScoreboardStreamRollbackSignal rollbackSignal
    ) {
        this.rollbackSignal = rollbackSignal;
        this.messageConverter = messageConverter;
        this.processor = processor;
        this.position = position;
        this.metrics = metrics;
        this.retryBackoffNanos = Math.max(1L, properties.retryBackoff().toNanos());
    }

    @Override
    public void onMessageBatch(List<Message> messages) {
        List<ContestScoreboardStreamEvent> events;
        try {
            events = decode(messages);
        } catch (RuntimeException undecodable) {
            // Nothing in this delivery reached the applier, so the standings end below its first
            // offset. Recorded before the failure is answered, because what the broker does with a
            // requeueing rejection is nothing at all: this delivery is only ever re-read by a
            // resubscribe, and until it is, no delivery above it may be applied.
            position.recordUnappliedRange(firstOffsetOrNone(messages));
            throw failBatch(undecodable);
        }
        if (events.isEmpty()) {
            return;
        }
        try {
            metrics.recordBatchStarted(events.stream()
                    .map(event -> event.message().judgedAt())
                    .min(java.time.LocalDateTime::compareTo)
                    .orElse(null));
            processor.process(events);
        } catch (ContestScoreboardCheckpointRegressedException regressed) {
            throw refuseRolledBackBatch(regressed);
        } catch (RuntimeException failure) {
            // The processor recorded the offset this batch stopped at before throwing; what is left is
            // to count the failure and put it behind a position that has to be established again.
            throw failBatch(failure);
        }
    }

    /**
     * Answers a batch that was not applied, and returns the exception for the caller to throw.
     *
     * <p>Counted, marked and un-verified in one place: the supervisor reads the count to decide the
     * batch needs a resubscribe, and the un-verified anchor is what makes the next delivery a question
     * about the range that was skipped instead of an ordinary step over it.</p>
     */
    private ImmediateRequeueAmqpException failBatch(RuntimeException failure) {
        metrics.recordFailure();
        position.recordFailedBatch();
        // The position is no longer verified, and the failure is what un-verifies it: this batch moved
        // the consumer past results the scoreboard did not account for. Without this the next delivery
        // above the checkpoint would be applied as an ordinary forward step - which is how a delivery
        // arriving after a failed batch used to move the checkpoint over the offset that failed,
        // leaving that result unreachable from the stream and from the checkpoint alike. Dropping the
        // verification makes the next forward step a gap question, and a gap is answered by the
        // recovery mode's own basis or refused, never stepped over silently.
        position.clearAnchorVerified();
        // Not "at the head for retry": a stream queue accepts a requeueing rejection without complaint
        // and does not hand the message back to the running consumer, so the retry comes from
        // ContestScoreboardStreamLifecycle resubscribing at the stored checkpoint. Measured against a
        // real broker in StreamQueueRequeueRabbitIntegrationTests.
        // The offset the checkpoint may not pass, not the applied watermark: a batch that failed
        // halfway applied its earlier deliveries, so the watermark sits below the checkpoint and
        // naming it here would report the scoreboard as shorter than it is.
        long unappliedFrom = position.unappliedFrom();
        log.error("Scoreboard stream batch failed and was left unapplied; the checkpoint does not move past "
                        + "{} until the consumer resubscribes and re-reads the batch",
                unappliedFrom < 0L ? "the delivery that failed" : "offset " + unappliedFrom,
                failure);
        LockSupport.parkNanos(retryBackoffNanos);
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
        }
        return new ImmediateRequeueAmqpException("Retry scoreboard stream batch", failure);
    }

    /**
     * Answers a batch the checkpoint CAS refused, and returns the exception for the caller to throw.
     *
     * <p>Not {@link #failBatch}: nothing was written and nothing failed, so there is no failure to count,
     * no failed batch for the supervisor to resubscribe for, and no unapplied range to hold back. The
     * processor already dropped the anchor. What is left is the rollback answer, and it is asked for now
     * rather than on the supervisor's next pass - for {@code stream-offset} that is the resubscribe at the
     * stored checkpoint that re-reads what the rollback took away. The request runs on another thread,
     * because stopping the container from its own consumer thread would wait on this very call.</p>
     *
     * <p>No backoff: until the resubscribe lands, each further batch meets the same floor and is refused
     * by one script call that writes nothing, and returning promptly is what lets the container stop.</p>
     */
    private ImmediateRequeueAmqpException refuseRolledBackBatch(ContestScoreboardCheckpointRegressedException regressed) {
        position.clearAnchorVerified();
        rollbackSignal.checkpointRegressed(regressed.consumerGeneration(), regressed.expectedFloor());
        return new ImmediateRequeueAmqpException("Scoreboard checkpoint rolled back; resubscribing", regressed);
    }

    /**
     * The first offset a delivery carried, or {@code -1} when it carried none this method can read.
     *
     * <p>A delivery whose offsets cannot be read cannot be described as a range, so there is nothing to
     * hold back; the un-verified anchor still makes the next delivery a question about the range below
     * it. Logged rather than thrown, because this runs while a failure is already being answered.</p>
     */
    private static long firstOffsetOrNone(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return -1L;
        }
        try {
            return streamOffset(messages.get(0));
        } catch (RuntimeException unreadable) {
            log.warn("A failed scoreboard stream batch carried no readable offset; the range it left "
                    + "unapplied is not held back", unreadable);
            return -1L;
        }
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
