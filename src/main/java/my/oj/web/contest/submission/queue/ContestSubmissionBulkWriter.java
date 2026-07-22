package my.oj.web.contest.submission.queue;

import jakarta.annotation.PreDestroy;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(prefix = "contest.submission.writer", name = "mode", havingValue = "bulk", matchIfMissing = true)
public class ContestSubmissionBulkWriter implements ContestSubmissionQueuedWriter {

    private final ContestSubmissionBulkProcessor processor;
    private final ContestSubmissionBulkMetrics metrics;
    private final ContestSubmissionCompletionDispatcher completionDispatcher;
    private final ConcurrentLinkedQueue<PendingSubmission> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final ExecutorService executor;
    private final int batchSize;
    private final int workerCount;

    public ContestSubmissionBulkWriter(ContestSubmissionBulkProcessor processor,
                                       ContestSubmissionBulkMetrics metrics,
                                       ContestSubmissionCompletionDispatcher completionDispatcher,
                                       ContestSubmissionBulkProperties properties) {
        this.processor = processor;
        this.metrics = metrics;
        this.completionDispatcher = completionDispatcher;
        this.batchSize = properties.effectiveBatchSize();
        this.workerCount = properties.effectiveWorkerCount();
        this.executor = Executors.newFixedThreadPool(this.workerCount, r -> {
            Thread thread = new Thread(r, "contest-submission-bulk");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ContestSubmissionService.ContestSubmissionCreateResult save(ContestSubmissionQueueRequest request) {
        return saveAsync(request).toCompletableFuture().join();
    }

    @Override
    public CompletionStage<ContestSubmissionService.ContestSubmissionCreateResult> saveAsync(
            ContestSubmissionQueueRequest request
    ) {
        CompletableFuture<ContestSubmissionService.ContestSubmissionCreateResult> future = new CompletableFuture<>();
        queue.add(new PendingSubmission(request, future));
        pendingCount.incrementAndGet();
        triggerFlushIfNecessary();
        return future;
    }

    @Scheduled(fixedDelayString = "${contest.submission.bulk.flush-interval-millis:200}")
    public void scheduledFlush() {
        if (pendingCount.get() > 0 && activeWorkers.get() == 0) {
            triggerPartialFlush();
        }
    }

    private void triggerFlushIfNecessary() {
        if (pendingCount.get() >= batchSize) {
            triggerFullBatchFlush();
        }
    }

    private void triggerFullBatchFlush() {
        while (pendingCount.get() >= batchSize) {
            int current = activeWorkers.get();
            if (current >= workerCount) {
                return;
            }
            int totalDemand = pendingCount.get() + (current * batchSize);
            int fullBatchWorkersNeeded = totalDemand / batchSize;
            if (fullBatchWorkersNeeded <= current) {
                return;
            }
            if (activeWorkers.compareAndSet(current, current + 1)) {
                executor.execute(this::drainFullBatches);
            }
        }
    }

    private void triggerPartialFlush() {
        while (pendingCount.get() > 0) {
            int current = activeWorkers.get();
            if (current >= workerCount) {
                return;
            }
            if (activeWorkers.compareAndSet(current, current + 1)) {
                executor.execute(this::drainAllPending);
                return;
            }
        }
    }

    private void drainFullBatches() {
        try {
            while (true) {
                List<PendingSubmission> chunk = pollChunk(false);
                if (chunk.isEmpty()) {
                    break;
                }
                processChunk(chunk);
            }
        } finally {
            activeWorkers.decrementAndGet();
            if (pendingCount.get() >= batchSize) {
                triggerFullBatchFlush();
            }
        }
    }

    private void drainAllPending() {
        try {
            while (true) {
                List<PendingSubmission> chunk = pollChunk(true);
                if (chunk.isEmpty()) {
                    break;
                }
                processChunk(chunk);
            }
        } finally {
            activeWorkers.decrementAndGet();
            if (pendingCount.get() >= batchSize) {
                triggerFullBatchFlush();
            }
        }
    }

    private List<PendingSubmission> pollChunk(boolean allowPartial) {
        if (!allowPartial && pendingCount.get() < batchSize) {
            return List.of();
        }
        List<PendingSubmission> chunk = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            PendingSubmission pending = queue.poll();
            if (pending == null) {
                break;
            }
            pendingCount.decrementAndGet();
            chunk.add(pending);
        }
        return chunk;
    }

    private void processChunk(List<PendingSubmission> chunk) {
        int pendingBefore = pendingCount.get();
        long startedAt = System.nanoTime();
        try {
            List<ContestSubmissionService.ContestSubmissionCreateResult> results = processor.process(
                    chunk.stream().map(PendingSubmission::request).toList()
            );
            if (results.size() != chunk.size()) {
                throw new IllegalStateException("Bulk processor returned a different result count");
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            metrics.recordSuccess(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
            completionDispatcher.dispatch(chunk.size(), () -> {
                for (int i = 0; i < chunk.size(); i++) {
                    chunk.get(i).future().complete(results.get(i));
                }
            });
        } catch (Exception ex) {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            metrics.recordFailure(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
            completionDispatcher.dispatch(
                    chunk.size(),
                    () -> chunk.forEach(pending -> pending.future().completeExceptionally(ex))
            );
        }
    }

    private record PendingSubmission(ContestSubmissionQueueRequest request,
                                     CompletableFuture<ContestSubmissionService.ContestSubmissionCreateResult> future) {
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
