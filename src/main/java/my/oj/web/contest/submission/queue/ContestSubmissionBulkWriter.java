package my.oj.web.contest.submission.queue;

import jakarta.annotation.PreDestroy;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import my.oj.web.contest.submission.core.ContestSubmissionWriter;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        // The gauges read these at scrape time. Nothing is pushed: a value captured when a chunk
        // finished would stop moving in the case worth watching, a queue filling while every
        // worker is stuck.
        metrics.bindSubmissionQueue(
                pendingCount::get, activeWorkers::get, this::currentInFlight, workerCount, maxInFlight);
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
        processChunk(chunk, true);
    }

    private void processChunk(List<PendingSubmission> chunk, boolean isolateInconsistentSubmissions) {
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
            if (!permitsReleased
                    && isolateInconsistentSubmissions
                    && ex instanceof ContestSubmissionBatchConsistencyException consistencyFailure) {
                ChunkPartition partition = partitionInconsistent(chunk, consistencyFailure);
                if (!partition.offenders().isEmpty() && !partition.remainder().isEmpty()) {
                    failSubmissions(
                            partition.offenders(),
                            consistencyFailure,
                            elapsedMillis,
                            pendingBefore
                    );
                    // The rollback undid every insert, and reserved ids are assigned per request, so
                    // replaying the survivors is safe. Isolation is off for the retry to bound the
                    // work at two attempts per chunk.
                    processChunk(partition.remainder(), false);
                    return;
                }
            }
            if (!permitsReleased) {
                failSubmissions(chunk, ex, elapsedMillis, pendingBefore);
                return;
            }
            metrics.recordFailure(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
            dispatchOrComplete(
                    chunk.size(),
                    () -> chunk.forEach(pending -> pending.future().completeExceptionally(ex))
            );
        }
    }

    private void failSubmissions(List<PendingSubmission> submissions,
                                 RuntimeException failure,
                                 long elapsedMillis,
                                 int pendingBefore) {
        metrics.recordFailure(
                submissions.size(),
                elapsedMillis,
                pendingBefore,
                pendingCount.get(),
                activeWorkers.get()
        );
        releaseChunk(submissions);
        dispatchOrComplete(
                submissions.size(),
                () -> submissions.forEach(pending -> pending.future().completeExceptionally(failure))
        );
    }

    /**
     * Splits a chunk into the submissions the batch could not account for and the ones that only
     * failed because they shared a transaction with them.
     */
    private static ChunkPartition partitionInconsistent(List<PendingSubmission> chunk,
                                                        ContestSubmissionBatchConsistencyException failure) {
        Set<Long> offendingIds = new HashSet<>(failure.offendingSubmissionIds());
        if (offendingIds.isEmpty()) {
            return new ChunkPartition(List.of(), chunk);
        }

        // The processor collapses in-batch duplicates before inserting, so a request that shares a
        // dedup key with an offender never reaches the insert and never appears in the reported ids.
        // The key includes the user id, so it would fail for the same reason on the retry.
        Set<DedupKey> offendingKeys = new HashSet<>();
        for (PendingSubmission pending : chunk) {
            Long reservedId = pending.request().reservedSubmissionId();
            if (reservedId != null && offendingIds.contains(reservedId)) {
                offendingKeys.add(DedupKey.from(pending.request()));
            }
        }

        List<PendingSubmission> offenders = new ArrayList<>();
        List<PendingSubmission> remainder = new ArrayList<>();
        for (PendingSubmission pending : chunk) {
            if (offendingKeys.contains(DedupKey.from(pending.request()))) {
                offenders.add(pending);
            } else {
                remainder.add(pending);
            }
        }
        return new ChunkPartition(offenders, remainder);
    }

    private record ChunkPartition(List<PendingSubmission> offenders, List<PendingSubmission> remainder) {
    }

    private record DedupKey(long contestId, long problemId, long userId, String codeHash) {
        private static DedupKey from(ContestSubmissionWriteRequest request) {
            return new DedupKey(
                    request.contestId(),
                    request.problemId(),
                    request.userId(),
                    request.codeHash()
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
