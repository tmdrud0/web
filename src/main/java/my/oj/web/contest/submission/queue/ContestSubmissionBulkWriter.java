package my.oj.web.contest.submission.queue;

import jakarta.annotation.PreDestroy;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import my.oj.web.contest.submission.core.ContestSubmissionWriter;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ContestSubmissionBulkWriter implements ContestSubmissionWriter {

    private final ContestSubmissionBulkProcessor processor;
    private final ContestSubmissionBulkMetrics metrics;
    private final ContestSubmissionCompletionDispatcher completionDispatcher;
    private final ConcurrentLinkedQueue<PendingSubmission> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final Semaphore inFlightPermits;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Object lifecycleMonitor = new Object();
    private final ExecutorService executor;
    private final int batchSize;
    private final int workerCount;
    private final int maxInFlight;

    public ContestSubmissionBulkWriter(ContestSubmissionBulkProcessor processor,
                                       ContestSubmissionBulkMetrics metrics,
                                       ContestSubmissionCompletionDispatcher completionDispatcher,
                                       ContestSubmissionBulkProperties properties) {
        this.processor = processor;
        this.metrics = metrics;
        this.completionDispatcher = completionDispatcher;
        this.batchSize = properties.effectiveBatchSize();
        this.workerCount = properties.effectiveWorkerCount();
        this.maxInFlight = properties.effectiveMaxInFlight();
        this.inFlightPermits = new Semaphore(maxInFlight);
        this.executor = Executors.newFixedThreadPool(this.workerCount, r -> {
            Thread thread = new Thread(r, "contest-submission-bulk");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ContestSubmissionService.ContestSubmissionCreateResult save(ContestSubmissionWriteRequest request) {
        return saveAsync(request).toCompletableFuture().join();
    }

    @Override
    public CompletionStage<ContestSubmissionService.ContestSubmissionCreateResult> saveAsync(
            ContestSubmissionWriteRequest request
    ) {
        CompletableFuture<ContestSubmissionService.ContestSubmissionCreateResult> future = new CompletableFuture<>();
        synchronized (lifecycleMonitor) {
            if (!accepting.get() || !inFlightPermits.tryAcquire()) {
                metrics.recordRejectedSubmission();
                return CompletableFuture.failedFuture(new ContestSubmissionOverloadedException());
            }
            metrics.recordInFlight(currentInFlight());
            queue.add(new PendingSubmission(request, future));
            pendingCount.incrementAndGet();
        }
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
                executeDrain(this::drainFullBatches);
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
                executeDrain(this::drainAllPending);
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
        boolean permitsReleased = false;
        try {
            List<ContestSubmissionService.ContestSubmissionCreateResult> results = processor.process(
                    chunk.stream().map(PendingSubmission::request).toList()
            );
            if (results.size() != chunk.size()) {
                throw new IllegalStateException("Bulk processor returned a different result count");
            }
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            metrics.recordSuccess(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
            releaseChunk(chunk);
            permitsReleased = true;
            dispatchOrComplete(chunk.size(), () -> {
                for (int i = 0; i < chunk.size(); i++) {
                    chunk.get(i).future().complete(results.get(i));
                }
            });
        } catch (RuntimeException ex) {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            metrics.recordFailure(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
            if (!permitsReleased) {
                releaseChunk(chunk);
            }
            dispatchOrComplete(
                    chunk.size(),
                    () -> chunk.forEach(pending -> pending.future().completeExceptionally(ex))
            );
        }
    }

    private void dispatchOrComplete(int submissionCount, Runnable completion) {
        try {
            completionDispatcher.dispatch(submissionCount, completion);
        } catch (RuntimeException ex) {
            completion.run();
        }
    }

    private void releaseChunk(List<PendingSubmission> chunk) {
        inFlightPermits.release(chunk.size());
        metrics.recordInFlight(currentInFlight());
    }

    private void executeDrain(Runnable drain) {
        try {
            executor.execute(drain);
        } catch (RejectedExecutionException ex) {
            activeWorkers.decrementAndGet();
            failQueuedSubmissions();
        }
    }

    private void failQueuedSubmissions() {
        PendingSubmission pending;
        while ((pending = queue.poll()) != null) {
            pendingCount.decrementAndGet();
            inFlightPermits.release();
            pending.future().completeExceptionally(new ContestSubmissionOverloadedException());
        }
        metrics.recordInFlight(currentInFlight());
    }

    private int currentInFlight() {
        return maxInFlight - inFlightPermits.availablePermits();
    }

    private record PendingSubmission(ContestSubmissionWriteRequest request,
                                     CompletableFuture<ContestSubmissionService.ContestSubmissionCreateResult> future) {
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lifecycleMonitor) {
            accepting.set(false);
            failQueuedSubmissions();
        }
        executor.shutdownNow();
    }
}
