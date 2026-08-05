package my.oj.web.contest.submission.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Drives the real writer and the real completion dispatcher and reads the gauges back out of a
 * SimpleMeterRegistry, the way CgroupResourceMetricsTests drives that binder against a temp cgroup
 * tree. What is under test is the wiring: the suppliers handed over at construction have to reach
 * the writer's own counters and the executor, not a copy of them taken at some earlier moment.
 *
 * <p>Each case parks the pipeline in a known state and asserts while it is parked, rather than
 * sampling a moving system.
 */
class ContestSubmissionQueueGaugeTests {

    private static final int MAX_IN_FLIGHT = 4;
    private static final int WORKER_COUNT = 1;
    private static final int BATCH_SIZE = 1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
    private final ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
    private final CountDownLatch chunkStarted = new CountDownLatch(1);
    private final CountDownLatch releaseChunk = new CountDownLatch(1);

    private ContestSubmissionBulkWriter writer;
    private ContestSubmissionCompletionDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        releaseChunk.countDown();
        if (writer != null) {
            writer.shutdown();
        }
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    void queueGaugesFollowTheWriterWhileAChunkIsInProgress() throws Exception {
        startWriter();

        // One submission per chunk and one worker, so this submission occupies the only worker
        // and blocks inside the processor while the next two queue up behind it.
        CompletionStage<?> first = writer.saveAsync(request("first"));
        assertThat(chunkStarted.await(5, TimeUnit.SECONDS)).isTrue();
        writer.saveAsync(request("second"));
        writer.saveAsync(request("third"));

        assertThat(gauge("contest.submission.bulk.active.workers")).isEqualTo(1.0);
        assertThat(gauge("contest.submission.bulk.queue.depth")).isEqualTo(2.0);
        assertThat(gauge("contest.submission.in_flight"))
                .as("all three hold an admission permit until their chunk commits")
                .isEqualTo(3.0);

        releaseChunk.countDown();
        first.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /**
     * Every assertion here expects zero, which an unwired gauge also reports, so the ceiling is
     * asserted alongside them. Without it this passes with the bind call deleted and says nothing.
     */
    @Test
    void queueGaugesReturnToZeroOnceEverythingDrains() throws Exception {
        startWriter();
        releaseChunk.countDown();

        writer.saveAsync(request("only")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        awaitZero("contest.submission.bulk.active.workers");

        assertThat(gauge("contest.submission.in_flight.limit"))
                .as("a zero here would mean the gauges are unbound rather than drained")
                .isEqualTo(MAX_IN_FLIGHT);
        assertThat(gauge("contest.submission.bulk.queue.depth")).isZero();
        assertThat(gauge("contest.submission.in_flight")).isZero();
    }

    @Test
    void writerPublishesItsConfiguredCeilings() {
        startWriter();

        assertThat(gauge("contest.submission.in_flight.limit")).isEqualTo(MAX_IN_FLIGHT);
        assertThat(gauge("contest.submission.bulk.workers.limit")).isEqualTo(WORKER_COUNT);
    }

    @Test
    void completionGaugesFollowTheExecutorWhileATaskIsRunning() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        startDispatcher(1, 8);

        // One thread, so the first task occupies it and the rest sit in the queue.
        dispatcher.dispatch(1, () -> {
            taskStarted.countDown();
            await(releaseTask);
        });
        assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch(1, () -> { });
        dispatcher.dispatch(1, () -> { });

        assertThat(gauge("contest.submission.completion.active")).isEqualTo(1.0);
        assertThat(gauge("contest.submission.completion.queue.depth")).isEqualTo(2.0);

        releaseTask.countDown();
    }

    @Test
    void dispatcherPublishesItsConfiguredCeilings() {
        startDispatcher(3, 17);

        assertThat(gauge("contest.submission.completion.threads")).isEqualTo(3.0);
        assertThat(gauge("contest.submission.completion.queue.capacity")).isEqualTo(17.0);
    }

    private void startWriter() {
        metrics.bindTo(registry);
        dispatcher = new ContestSubmissionCompletionDispatcher(
                metrics, new ContestSubmissionCompletionProperties(1, 64));
        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                dispatcher,
                new ContestSubmissionBulkProperties(BATCH_SIZE, WORKER_COUNT, MAX_IN_FLIGHT));
        given(processor.process(anyList())).willAnswer(invocation -> {
            chunkStarted.countDown();
            await(releaseChunk);
            List<ContestSubmissionWriteRequest> requests = invocation.getArgument(0);
            return requests.stream().map(ignored -> result()).toList();
        });
    }

    private void startDispatcher(int threadCount, int queueCapacity) {
        metrics.bindTo(registry);
        dispatcher = new ContestSubmissionCompletionDispatcher(
                metrics, new ContestSubmissionCompletionProperties(threadCount, queueCapacity));
    }

    /**
     * A worker decrements its own counter after the future completes, so the completing thread can
     * observe the write before the worker has stood down.
     */
    private void awaitZero(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gauge(name) != 0.0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(gauge(name)).isZero();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ContestSubmissionService.ContestSubmissionCreateResult result() {
        return new ContestSubmissionService.ContestSubmissionCreateResult(
                ContestSubmission.placeholder(1L), false);
    }

    private static ContestSubmissionWriteRequest request(String codeHash) {
        return new ContestSubmissionWriteRequest(
                1L, 1L, 1L, "return 0;", codeHash, LocalDateTime.of(2026, 3, 10, 12, 0), null);
    }
}
