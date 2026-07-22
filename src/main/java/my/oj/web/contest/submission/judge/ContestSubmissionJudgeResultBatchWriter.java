package my.oj.web.contest.submission.judge;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(
        prefix = "contest.submission.judge.result-writer",
        name = "mode",
        havingValue = "batch"
)
public class ContestSubmissionJudgeResultBatchWriter implements ContestSubmissionJudgeResultWriter {

    private final JdbcContestSubmissionJudgeResultBatchPersistence persistence;
    private final BlockingQueue<PendingResult> queue;
    private final ExecutorService executor;
    private final int batchSize;
    private final int workerCount;
    private final long maxWaitNanos;
    private volatile boolean running;

    public ContestSubmissionJudgeResultBatchWriter(
            JdbcContestSubmissionJudgeResultBatchPersistence persistence,
            ContestSubmissionJudgeResultWriterProperties properties
    ) {
        this.persistence = persistence;
        this.batchSize = properties.effectiveBatchSize();
        this.workerCount = properties.effectiveWorkerCount();
        this.queue = new ArrayBlockingQueue<>(properties.effectiveQueueCapacity());
        this.maxWaitNanos = properties.effectiveMaxWaitNanos();
        AtomicInteger threadSequence = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(this.workerCount, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "contest-judge-result-batch-" + threadSequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void start() {
        running = true;
        for (int i = 0; i < workerCount; i++) {
            executor.execute(this::run);
        }
    }

    @Override
    public void persist(ContestSubmissionJudgeProjection submission,
                        SubmissionResult result,
                        LocalDateTime judgedAt) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        PendingResult pending = new PendingResult(
                ContestSubmissionJudgeResultCommand.from(submission, result, judgedAt),
                completion
        );

        try {
            queue.put(pending);
            completion.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while queueing judge result", ex);
        } catch (CompletionException ex) {
            throw propagate(ex.getCause());
        }
    }

    private void run() {
        try {
            while (running || !queue.isEmpty()) {
                PendingResult first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                process(collectBatch(first));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            failRemaining(new IllegalStateException("Judge result batch writer stopped"));
        }
    }

    private List<PendingResult> collectBatch(PendingResult first) throws InterruptedException {
        List<PendingResult> batch = new ArrayList<>(batchSize);
        batch.add(first);
        queue.drainTo(batch, batchSize - 1);

        long deadline = System.nanoTime() + maxWaitNanos;
        while (batch.size() < batchSize) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            PendingResult next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
            queue.drainTo(batch, batchSize - batch.size());
        }
        return batch;
    }

    private void process(List<PendingResult> batch) {
        try {
            persistence.persistAll(batch.stream().map(PendingResult::command).toList());
            batch.forEach(pending -> pending.completion().complete(null));
        } catch (RuntimeException ex) {
            batch.forEach(pending -> pending.completion().completeExceptionally(ex));
        }
    }

    private void failRemaining(RuntimeException failure) {
        PendingResult pending;
        while ((pending = queue.poll()) != null) {
            pending.completion().completeExceptionally(failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Failed to persist judge result batch", failure);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record PendingResult(ContestSubmissionJudgeResultCommand command,
                                 CompletableFuture<Void> completion) {
    }
}
