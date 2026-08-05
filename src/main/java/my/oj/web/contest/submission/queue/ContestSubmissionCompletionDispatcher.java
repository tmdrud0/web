package my.oj.web.contest.submission.queue;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ContestSubmissionCompletionDispatcher {

    private final ContestSubmissionBulkMetrics metrics;
    private final ThreadPoolExecutor executor;

    public ContestSubmissionCompletionDispatcher(
            ContestSubmissionBulkMetrics metrics,
            ContestSubmissionCompletionProperties properties
    ) {
        this.metrics = metrics;
        AtomicInteger threadSequence = new AtomicInteger();
        int effectiveThreadCount = properties.effectiveThreadCount();
        this.executor = new ThreadPoolExecutor(
                effectiveThreadCount,
                effectiveThreadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.effectiveQueueCapacity()),
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
        // Read at scrape time straight off the executor, which already tracks both numbers. The
        // queue capacity comes from properties because ArrayBlockingQueue reports only what is
        // left, and remainingCapacity() + size() is two reads of a queue that moves between them.
        metrics.bindCompletionExecutor(
                () -> executor.getQueue().size(),
                executor::getActiveCount,
                properties.effectiveQueueCapacity(),
                effectiveThreadCount);
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
