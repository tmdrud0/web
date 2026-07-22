package my.oj.web.contest.submission.queue;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

@Component
public class ContestSubmissionBulkMetrics {

    private final LongAdder chunkCount = new LongAdder();
    private final LongAdder totalChunkElapsedMillis = new LongAdder();
    private final LongAdder totalSubmissionCount = new LongAdder();
    private final LongAdder failedChunkCount = new LongAdder();
    private final LongAccumulator maxChunkElapsedMillis = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxChunkSize = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxPendingBefore = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxPendingAfter = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxActiveWorkers = new LongAccumulator(Math::max, 0L);
    private final AtomicInteger lastPendingAfter = new AtomicInteger();
    private final LongAdder completionTaskCount = new LongAdder();
    private final LongAdder completionSubmissionCount = new LongAdder();
    private final LongAdder totalCompletionQueueDelayMillis = new LongAdder();
    private final LongAdder totalCompletionElapsedMillis = new LongAdder();
    private final LongAdder failedCompletionTaskCount = new LongAdder();
    private final LongAdder completionCallerRunsCount = new LongAdder();
    private final LongAccumulator maxCompletionQueueDelayMillis = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxCompletionElapsedMillis = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxCompletionQueueDepth = new LongAccumulator(Math::max, 0L);
    private final LongAccumulator maxActiveCompletionWorkers = new LongAccumulator(Math::max, 0L);

    public void recordSuccess(int chunkSize, long elapsedMillis, int pendingBefore, int pendingAfter, int activeWorkers) {
        chunkCount.increment();
        totalChunkElapsedMillis.add(elapsedMillis);
        totalSubmissionCount.add(chunkSize);
        maxChunkElapsedMillis.accumulate(elapsedMillis);
        maxChunkSize.accumulate(chunkSize);
        maxPendingBefore.accumulate(pendingBefore);
        maxPendingAfter.accumulate(pendingAfter);
        maxActiveWorkers.accumulate(activeWorkers);
        lastPendingAfter.set(pendingAfter);
    }

    public void recordFailure(int chunkSize, long elapsedMillis, int pendingBefore, int pendingAfter, int activeWorkers) {
        failedChunkCount.increment();
        recordSuccess(chunkSize, elapsedMillis, pendingBefore, pendingAfter, activeWorkers);
    }

    public void recordCompletion(int submissionCount,
                                 long queueDelayMillis,
                                 long elapsedMillis,
                                 int queueDepth,
                                 int activeWorkers,
                                 boolean failed) {
        completionTaskCount.increment();
        completionSubmissionCount.add(submissionCount);
        totalCompletionQueueDelayMillis.add(queueDelayMillis);
        totalCompletionElapsedMillis.add(elapsedMillis);
        maxCompletionQueueDelayMillis.accumulate(queueDelayMillis);
        maxCompletionElapsedMillis.accumulate(elapsedMillis);
        recordCompletionExecutorState(queueDepth, activeWorkers);
        if (failed) {
            failedCompletionTaskCount.increment();
        }
    }

    public void recordCompletionExecutorState(int queueDepth, int activeWorkers) {
        maxCompletionQueueDepth.accumulate(queueDepth);
        maxActiveCompletionWorkers.accumulate(activeWorkers);
    }

    public void recordCompletionCallerRuns() {
        completionCallerRunsCount.increment();
    }

    public Snapshot snapshot() {
        long chunks = chunkCount.sum();
        long totalElapsed = totalChunkElapsedMillis.sum();
        long completionTasks = completionTaskCount.sum();
        return new Snapshot(
                chunks,
                failedChunkCount.sum(),
                totalSubmissionCount.sum(),
                chunks == 0 ? 0.0 : (double) totalElapsed / chunks,
                maxChunkElapsedMillis.get(),
                maxChunkSize.get(),
                maxPendingBefore.get(),
                maxPendingAfter.get(),
                lastPendingAfter.get(),
                maxActiveWorkers.get(),
                completionTasks,
                failedCompletionTaskCount.sum(),
                completionSubmissionCount.sum(),
                completionTasks == 0 ? 0.0 : (double) totalCompletionQueueDelayMillis.sum() / completionTasks,
                maxCompletionQueueDelayMillis.get(),
                completionTasks == 0 ? 0.0 : (double) totalCompletionElapsedMillis.sum() / completionTasks,
                maxCompletionElapsedMillis.get(),
                maxCompletionQueueDepth.get(),
                maxActiveCompletionWorkers.get(),
                completionCallerRunsCount.sum()
        );
    }

    public void reset() {
        chunkCount.reset();
        totalChunkElapsedMillis.reset();
        totalSubmissionCount.reset();
        failedChunkCount.reset();
        maxChunkElapsedMillis.reset();
        maxChunkSize.reset();
        maxPendingBefore.reset();
        maxPendingAfter.reset();
        maxActiveWorkers.reset();
        lastPendingAfter.set(0);
        completionTaskCount.reset();
        completionSubmissionCount.reset();
        totalCompletionQueueDelayMillis.reset();
        totalCompletionElapsedMillis.reset();
        failedCompletionTaskCount.reset();
        completionCallerRunsCount.reset();
        maxCompletionQueueDelayMillis.reset();
        maxCompletionElapsedMillis.reset();
        maxCompletionQueueDepth.reset();
        maxActiveCompletionWorkers.reset();
    }

    public record Snapshot(long chunkCount,
                           long failedChunkCount,
                           long totalSubmissionCount,
                           double averageChunkElapsedMillis,
                           long maxChunkElapsedMillis,
                           long maxChunkSize,
                           long maxPendingBefore,
                           long maxPendingAfter,
                           int lastPendingAfter,
                           long maxActiveWorkers,
                           long completionTaskCount,
                           long failedCompletionTaskCount,
                           long completionSubmissionCount,
                           double averageCompletionQueueDelayMillis,
                           long maxCompletionQueueDelayMillis,
                           double averageCompletionElapsedMillis,
                           long maxCompletionElapsedMillis,
                           long maxCompletionQueueDepth,
                           long maxActiveCompletionWorkers,
                           long completionCallerRunsCount) {
    }
}
