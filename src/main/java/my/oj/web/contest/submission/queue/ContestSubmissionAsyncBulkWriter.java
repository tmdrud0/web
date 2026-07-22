package my.oj.web.contest.submission.queue;

import jakarta.annotation.PreDestroy;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("perf")
@ConditionalOnProperty(prefix = "contest.submission.writer", name = "mode", havingValue = "bulk-async")
public class ContestSubmissionAsyncBulkWriter implements ContestSubmissionQueuedWriter {

    private static final Logger log = LoggerFactory.getLogger(ContestSubmissionAsyncBulkWriter.class);

    private final ContestSubmissionBulkProcessor processor;
    private final ContestSubmissionBulkMetrics metrics;
    private final ContestSubmissionIdGenerator idGenerator;
    private final ConcurrentLinkedQueue<ContestSubmissionQueueRequest> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final ExecutorService executor;
    private final int batchSize;
    private final int workerCount;

    public ContestSubmissionAsyncBulkWriter(ContestSubmissionBulkProcessor processor,
                                            ContestSubmissionBulkMetrics metrics,
                                            ContestSubmissionIdGenerator idGenerator,
                                            ContestSubmissionBulkProperties properties) {
        this.processor = processor;
        this.metrics = metrics;
        this.idGenerator = idGenerator;
        this.batchSize = properties.effectiveBatchSize();
        this.workerCount = properties.effectiveWorkerCount();
        this.executor = Executors.newFixedThreadPool(this.workerCount, r -> {
            Thread thread = new Thread(r, "contest-submission-bulk-async");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public ContestSubmissionService.ContestSubmissionCreateResult save(ContestSubmissionQueueRequest request) {
        long submissionId = request.reservedSubmissionId() != null
                ? request.reservedSubmissionId()
                : idGenerator.nextId();
        queue.add(request.reservedSubmissionId() != null ? request : request.withReservedSubmissionId(submissionId));
        pendingCount.incrementAndGet();
        triggerFlushIfNecessary();
        return new ContestSubmissionService.ContestSubmissionCreateResult(
                ContestSubmission.placeholder(submissionId),
                false
        );
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
                List<ContestSubmissionQueueRequest> chunk = pollChunk(false);
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
                List<ContestSubmissionQueueRequest> chunk = pollChunk(true);
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

    private List<ContestSubmissionQueueRequest> pollChunk(boolean allowPartial) {
        if (!allowPartial && pendingCount.get() < batchSize) {
            return List.of();
        }
        List<ContestSubmissionQueueRequest> chunk = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            ContestSubmissionQueueRequest pending = queue.poll();
            if (pending == null) {
                break;
            }
            pendingCount.decrementAndGet();
            chunk.add(pending);
        }
        return chunk;
    }

    private void processChunk(List<ContestSubmissionQueueRequest> chunk) {
        int pendingBefore = pendingCount.get();
        long startedAt = System.nanoTime();
        try {
            processor.process(chunk);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            metrics.recordSuccess(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
        } catch (Exception ex) {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            metrics.recordFailure(chunk.size(), elapsedMillis, pendingBefore, pendingCount.get(), activeWorkers.get());
            log.error("Contest submission async bulk chunk failed", ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
