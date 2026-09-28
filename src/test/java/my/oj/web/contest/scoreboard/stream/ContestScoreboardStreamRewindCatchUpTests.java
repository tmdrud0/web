package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryCutover;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.Outcome;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stream-offset rewind whose catch-up spans several batches, run through the real listener, processor,
 * position and lifecycle against a store that enforces the checkpoint floor and a broker that serves
 * whatever the consumer was last pointed at.
 *
 * <p>The defect these pin: after the rewind, every catch-up batch past the first was judged against the
 * applied watermark - which stays at the pre-rollback tip on purpose - and taken for a fresh rollback. The
 * live path dropped the anchor and refused the batch, the supervisor rewound again at wherever the re-read
 * had got to, and the catch-up moved one batch per supervisor interval. The supervisor did the same on its
 * own, because each pass saw a (stored, applied) pair it had not answered yet.</p>
 *
 * <p>The supervisor pass runs after every batch here, which is far more often than its real interval: a
 * catch-up that survives that survives any cadence.</p>
 */
class ContestScoreboardStreamRewindCatchUpTests {

    private static final int BATCH_SIZE = 5;

    private FloorCheckingStore store;
    private FakeBroker broker;
    private ContestScoreboardStreamPosition position;
    private SimpleMeterRegistry registry;
    private ContestScoreboardRecoveryStrategy strategy;
    private ContestScoreboardStreamListener listener;
    private ContestScoreboardStreamLifecycle lifecycle;
    private int refusedDeliveries;

    @BeforeEach
    void setUp() {
        store = new FloorCheckingStore();
        broker = new FakeBroker();
        position = new ContestScoreboardStreamPosition();
        registry = new SimpleMeterRegistry();
        ContestScoreboardStreamMetrics metrics = new ContestScoreboardStreamMetrics(registry);
        strategy = mock(ContestScoreboardRecoveryStrategy.class);
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.STREAM_OFFSET);
        lenient().when(strategy.rewindsOnCheckpointRegression()).thenReturn(true);
        lenient().when(strategy.recoversHistoryBeforeConsuming()).thenReturn(false);
        // StreamOffsetRecoveryStrategy's answer: a range inside what this JVM applied is the rewind's to
        // repair, so the live path is refused it.
        lenient().when(strategy.rebuildHistory(any())).thenAnswer(invocation -> {
            ContestScoreboardRecoveryStrategy.LostRange range = invocation.getArgument(0);
            return range.withinAppliedHistory() ? Outcome.RETRYABLE_FAILURE : Outcome.UNRECOVERABLE;
        });
        ContestScoreboardAppliedAtCompletion completion = mock(ContestScoreboardAppliedAtCompletion.class);
        ContestScoreboardStreamProcessor processor = new ContestScoreboardStreamProcessor(
                store, completion, position, strategy, metrics, new ContestScoreboardApplyLock());
        MessageConverter converter = mock(MessageConverter.class);
        when(converter.fromMessage(any())).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            return payload(((Number) message.getMessageProperties().getHeaders().get("x-stream-offset")).longValue());
        });
        ContestScoreboardStreamRollbackSignal signal = new ContestScoreboardStreamRollbackSignal();
        listener = new ContestScoreboardStreamListener(converter, processor, position, metrics,
                consumerProperties(), signal);

        SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
        lenient().when(container.getActiveConsumerCount()).thenReturn(1);
        doAnswer(invocation -> {
            Map<String, Object> arguments = invocation.getArgument(0);
            broker.seek(arguments.get("x-stream-offset"));
            return null;
        }).when(container).setConsumerArguments(anyMap());
        lifecycle = new ContestScoreboardStreamLifecycle(container, store, completion, position, metrics,
                recoveryProperties(), strategy, new ContestScoreboardRecoveryCutover(), signal, Runnable::run);
        lifecycle.start();
    }

    @Test
    void aRollbackSpanningSeveralBatchesIsRewoundOnceAndCaughtUpWithoutAnotherFailure() {
        FloorCheckingStore.Snapshot snapshot = consumeUpToARollback(10, 30);
        store.restore(snapshot);
        broker.publish(5);

        consumeWithASupervisorPassAfterEveryBatch();

        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).as("exactly one rewind").isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.failures")).as("no catch-up batch refused").isZero();
        assertThat(counter("contest.scoreboard.stream.failure.restarts")).isZero();
        assertThat(detections("apply-cas")).isEqualTo(1.0);
        assertThat(detections("supervisor")).isZero();
        assertThat(refusedDeliveries).as("only the delivery the CAS refused").isEqualTo(1);
        assertEverythingPublishedIsApplied();
        // The 30 offsets the rollback took away came back through the re-read, and only those are tagged
        // as replayed: everything else reached the standings for the first time.
        assertThat(staleness(true)).isEqualTo(30L);
        assertThat(staleness(false)).isEqualTo(45L);
        verify(strategy, never()).rebuildHistory(any());
    }

    @Test
    void aRollbackTheSupervisorFindsFirstIsAlsoRewoundOnlyOnce() {
        FloorCheckingStore.Snapshot snapshot = consumeUpToARollback(10, 30);
        store.restore(snapshot);

        lifecycle.recoverConsumption();
        broker.publish(5);
        consumeWithASupervisorPassAfterEveryBatch();

        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(1.0);
        assertThat(counter("contest.scoreboard.stream.failures")).isZero();
        assertThat(detections("supervisor")).isEqualTo(1.0);
        assertThat(detections("apply-cas")).isZero();
        assertThat(refusedDeliveries).isZero();
        assertEverythingPublishedIsApplied();
    }

    @Test
    void aSecondRollbackDuringTheCatchUpIsRefusedByTheCasAndRewoundWithoutSkippingAnything() {
        FloorCheckingStore.Snapshot first = consumeUpToARollback(10, 30);
        store.restore(first);
        broker.publish(5);
        deliverAndSupervise(2);   // the refused delivery and the first catch-up batch
        FloorCheckingStore.Snapshot duringCatchUp = store.snapshot();
        deliverAndSupervise(3);
        assertThat(store.currentStreamOffset()).as("still catching up").isLessThan(broker.offsetAt(39));

        store.restore(duringCatchUp);
        consumeWithASupervisorPassAfterEveryBatch();

        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(2.0);
        assertThat(detections("apply-cas")).isEqualTo(2.0);
        assertThat(counter("contest.scoreboard.stream.failures")).isZero();
        assertEverythingPublishedIsApplied();
    }

    /**
     * The same snapshot restored again during the catch-up shows the supervisor exactly the pair the first
     * rewind answered. It is still a new rollback: the checkpoint is below where the rewind had got to.
     */
    @Test
    void aSecondRestoreOfTheSameSnapshotDuringTheCatchUpIsRewoundByTheSupervisor() {
        FloorCheckingStore.Snapshot snapshot = consumeUpToARollback(10, 30);
        store.restore(snapshot);
        lifecycle.recoverConsumption();
        deliverAndSupervise(3);
        assertThat(store.currentStreamOffset()).isGreaterThan(snapshot.checkpoint());

        store.restore(snapshot);
        lifecycle.recoverConsumption();

        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(2.0);
        assertThat(detections("supervisor")).isEqualTo(2.0);
        consumeWithASupervisorPassAfterEveryBatch();
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(2.0);
        assertThat(counter("contest.scoreboard.stream.failures")).isZero();
        assertEverythingPublishedIsApplied();
    }

    /** And after the catch-up has finished, with nothing new published to move the applied watermark. */
    @Test
    void theSameSnapshotRestoredAfterTheCatchUpIsRewoundAgain() {
        FloorCheckingStore.Snapshot snapshot = consumeUpToARollback(10, 30);
        store.restore(snapshot);
        lifecycle.recoverConsumption();
        consumeWithASupervisorPassAfterEveryBatch();
        assertEverythingPublishedIsApplied();

        store.restore(snapshot);
        lifecycle.recoverConsumption();
        consumeWithASupervisorPassAfterEveryBatch();

        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isEqualTo(2.0);
        assertThat(counter("contest.scoreboard.stream.failures")).isZero();
        assertEverythingPublishedIsApplied();
    }

    // --- driving ------------------------------------------------------------------------------------

    /**
     * Publishes and consumes {@code beforeSnapshot} events, snapshots the store, then publishes and
     * consumes {@code afterSnapshot} more - the range a restore of the snapshot takes away.
     */
    private FloorCheckingStore.Snapshot consumeUpToARollback(int beforeSnapshot, int afterSnapshot) {
        broker.publish(beforeSnapshot);
        consumeWithASupervisorPassAfterEveryBatch();
        FloorCheckingStore.Snapshot snapshot = store.snapshot();
        broker.publish(afterSnapshot);
        consumeWithASupervisorPassAfterEveryBatch();
        assertThat(store.currentStreamOffset()).isEqualTo(broker.lastOffset());
        assertThat(counter("contest.scoreboard.stream.rollback.restarts")).isZero();
        return snapshot;
    }

    private void consumeWithASupervisorPassAfterEveryBatch() {
        for (int guard = 0; guard < 1_000; guard++) {
            if (!deliverBatch()) {
                return;
            }
            lifecycle.recoverConsumption();
        }
        throw new AssertionError("the consumer never reached the tail of the stream");
    }

    private void deliverAndSupervise(int batches) {
        for (int i = 0; i < batches; i++) {
            assertThat(deliverBatch()).isTrue();
            lifecycle.recoverConsumption();
        }
    }

    private boolean deliverBatch() {
        List<Message> batch = broker.nextBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return false;
        }
        try {
            listener.onMessageBatch(batch);
        } catch (ImmediateRequeueAmqpException refused) {
            refusedDeliveries++;
        }
        return true;
    }

    private void assertEverythingPublishedIsApplied() {
        assertThat(store.currentStreamOffset()).isEqualTo(broker.lastOffset());
        assertThat(store.standings()).containsExactlyElementsOf(broker.offsets());
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private double detections(String path) {
        return registry.get("contest.scoreboard.stream.rollback.detected").tag("path", path).counter().count();
    }

    private long staleness(boolean replayed) {
        return registry.get("contest.scoreboard.apply.staleness").tag("replayed", Boolean.toString(replayed))
                .timer().count();
    }

    // --- fakes --------------------------------------------------------------------------------------

    /**
     * The batched script's contract over a set of offsets standing in for the standings: the floor check
     * before anything is written, duplicates at or below the checkpoint, and a snapshot/restore that takes
     * the checkpoint and the standings back together - which is what an RDB restore does.
     */
    private static final class FloorCheckingStore implements ContestScoreboardApplier {

        record Snapshot(long checkpoint, TreeSet<Long> standings) {
        }

        private long checkpoint = -1L;
        private TreeSet<Long> standings = new TreeSet<>();

        synchronized Snapshot snapshot() {
            return new Snapshot(checkpoint, new TreeSet<>(standings));
        }

        synchronized void restore(Snapshot snapshot) {
            checkpoint = snapshot.checkpoint();
            standings = new TreeSet<>(snapshot.standings());
        }

        synchronized List<Long> standings() {
            return List.copyOf(standings);
        }

        @Override
        public synchronized Long apply(ApplyRequest request) {
            return applyAll(List.of(request), NO_CHECKPOINT_FLOOR).get(0).appliedOffset();
        }

        @Override
        public synchronized List<ApplyResult> applyAll(List<ApplyRequest> requests, long expectedCheckpointFloor) {
            if (expectedCheckpointFloor >= 0L && checkpoint < expectedCheckpointFloor) {
                return List.of(ApplyResult.rollback(requests.get(0).correlationId(), checkpoint));
            }
            List<ApplyResult> results = new ArrayList<>(requests.size());
            for (ApplyRequest request : requests) {
                long offset = request.streamOffset();
                if (offset <= checkpoint) {
                    results.add(ApplyResult.duplicate(request.correlationId(), checkpoint));
                    continue;
                }
                boolean fresh = standings.add(offset);
                checkpoint = offset;
                results.add(fresh
                        ? ApplyResult.success(request.correlationId(), checkpoint)
                        : ApplyResult.duplicate(request.correlationId(), checkpoint));
            }
            return results;
        }

        @Override
        public synchronized long currentStreamOffset() {
            return checkpoint;
        }

        @Override
        public void reset(long contestId) {
        }
    }

    /**
     * A stream that keeps every offset and serves the consumer from wherever it was last pointed.
     * Offsets are deliberately sparse: nothing may assume the one after {@code n} is {@code n + 1}.
     */
    private static final class FakeBroker {

        private final List<Long> offsets = new ArrayList<>();
        private int cursor;

        void publish(int count) {
            for (int i = 0; i < count; i++) {
                offsets.add(offsets.size() * 3L + 7L);
            }
        }

        void seek(Object requested) {
            if ("first".equals(requested)) {
                cursor = 0;
                return;
            }
            long from = ((Number) requested).longValue();
            cursor = 0;
            while (cursor < offsets.size() && offsets.get(cursor) < from) {
                cursor++;
            }
        }

        /** The next batch, with the cursor moved past it before the consumer sees it, as the broker does. */
        List<Message> nextBatch(int size) {
            int end = Math.min(cursor + size, offsets.size());
            List<Message> batch = new ArrayList<>();
            for (int i = cursor; i < end; i++) {
                batch.add(message(offsets.get(i)));
            }
            cursor = end;
            return batch;
        }

        List<Long> offsets() {
            return List.copyOf(offsets);
        }

        long offsetAt(int index) {
            return offsets.get(index);
        }

        long lastOffset() {
            return offsets.get(offsets.size() - 1);
        }
    }

    private static Message message(long offset) {
        MessageProperties properties = new MessageProperties();
        properties.getHeaders().put("x-stream-offset", offset);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    private static ContestJudgeResultStreamMessage payload(long offset) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        return new ContestJudgeResultStreamMessage(ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION,
                1_000L + offset, 10L, 20L, 30L, now.minusHours(1), now.minusMinutes(1), now,
                SubmissionResult.ACCEPTED);
    }

    private static ContestScoreboardStreamConsumerProperties consumerProperties() {
        return new ContestScoreboardStreamConsumerProperties(500, 500, Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(2), 4096);
    }

    private static ContestScoreboardRecoveryProperties recoveryProperties() {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.STREAM_OFFSET,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 500, 3,
                        Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED),
                new ContestScoreboardRecoveryProperties.RecoveryOwner(true));
    }
}
