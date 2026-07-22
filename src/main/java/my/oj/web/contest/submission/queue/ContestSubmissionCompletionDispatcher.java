package my.oj.web.contest.submission.queue;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(prefix = "contest.submission.writer", name = "mode", havingValue = "bulk", matchIfMissing = true)
public class ContestSubmissionCompletionDispatcher {

    private final ContestSubmissionBulkMetrics metrics;
    private final ThreadPoolExecutor executor;

    public ContestSubmissionCompletionDispatcher(
            ContestSubmissionBulkMetrics metrics,
            @Value("${contest.submission.completion.thread-count:8}") int threadCount,
            @Value("${contest.submission.completion.queue-capacity:256}") int queueCapacity
    ) {
        this.metrics = metrics;
        AtomicInteger threadSequence = new AtomicInteger();
        int effectiveThreadCount = Math.max(1, threadCount);
        this.executor = new ThreadPoolExecutor(
                effectiveThreadCount,
                effectiveThreadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "contest-submission-completion-" + threadSequence.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                (task, rejectedExecutor) -> {
                    metrics.recordCompletionCallerRuns();
                    task.run();
                }
        );
        this.executor.prestartAllCoreThreads();
    }

    public void dispatch(int submissionCount, Runnable completion) {
        long queuedAt = System.nanoTime();
        executor.execute(() -> {
            long startedAt = System.nanoTime();
            long queueDelayMillis = (startedAt - queuedAt) / 1_000_000;
            boolean failed = false;
            try {
                completion.run();
            } catch (RuntimeException | Error ex) {
                failed = true;
                throw ex;
            } finally {
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
                metrics.recordCompletion(
                        submissionCount,
                        queueDelayMillis,
                        elapsedMillis,
                        executor.getQueue().size(),
                        executor.getActiveCount(),
                        failed
                );
            }
        });
        metrics.recordCompletionExecutorState(executor.getQueue().size(), executor.getActiveCount());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
