package my.oj.web.contest.submission.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Records the submission pipeline's internal state twice, for two readers that need different
 * things.
 *
 * <p>{@link #snapshot()} feeds the perf endpoint, which a load-test harness reads once per run.
 * It keeps averages, per-process maxima and a {@link #reset()}, all of which suit a harness that
 * brackets a single run on a single JVM.
 *
 * <p>The meters registered by {@link #bindTo(MeterRegistry)} feed Prometheus, which reads
 * continuously across every web instance. Nothing in that set can be an average or a per-instance
 * maximum, because neither aggregates across web-1 and web-2: latency is carried by timer
 * histograms so a percentile can be computed over the summed buckets, and depth is carried by
 * last-value gauges so a peak comes from {@code max_over_time()} at query time.
 * {@link #reset()} deliberately does not touch them - a counter falling back to zero reads as a
 * process restart and resets {@code rate()}.
 */
@Component
public class ContestSubmissionBulkMetrics implements MeterBinder {

    /**
     * A chunk is a DB batch insert of up to {@code bulk.batch-size} submissions plus their outbox
     * rows, so the interesting range is milliseconds to a second. The buckets are set here rather
     * than in application.properties because they belong to this meter rather than to a profile.
     */
    private static final Duration[] CHUNK_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofSeconds(2)
    };

    /**
     * Completion work is in-memory future completion, so it is faster than a chunk and the low
     * buckets carry the signal. The call sites hand over whole milliseconds, so anything below
     * the first bucket collapses into it.
     */
    private static final Duration[] COMPLETION_BUCKETS = {
            Duration.ofMillis(1), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25),
            Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
            Duration.ofMillis(500), Duration.ofSeconds(1)
    };

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
    private final LongAdder rejectedSubmissionCount = new LongAdder();
    private final AtomicInteger currentInFlight = new AtomicInteger();
    private final LongAccumulator maxInFlight = new LongAccumulator(Math::max, 0L);

    /** Last-value state behind the gauges. Not part of the snapshot; see the class comment. */
    private final AtomicInteger inFlightLimit = new AtomicInteger();
    private final AtomicInteger lastCompletionQueueDepth = new AtomicInteger();

    /**
     * A composite registry with no children discards every recording, so the meters exist before
     * Spring binds this to the real registry and the record methods need no null check. Unit tests
     * that construct this class directly are never bound and take the same path.
     */
    private volatile Meters meters = Meters.of(new CompositeMeterRegistry());

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("contest.submission.in_flight", currentInFlight, AtomicInteger::get)
                .description("Submissions admitted but not yet committed to the database. Compare "
                        + "against contest.submission.in_flight.limit; the difference is the "
                        + "admission headroom before requests are shed with 503")
                .register(registry);
        Gauge.builder("contest.submission.in_flight.limit", inFlightLimit, AtomicInteger::get)
                .description("Configured max-in-flight admission ceiling for this instance")
                .register(registry);
        Gauge.builder("contest.submission.bulk.queue.depth", lastPendingAfter, AtomicInteger::get)
                .description("Submissions waiting for a bulk worker to pick them up. A last value "
                        + "rather than a peak: take the peak with max_over_time(), which a "
                        + "per-instance maximum could not give across web-1 and web-2")
                .register(registry);
        Gauge.builder("contest.submission.completion.queue.depth", lastCompletionQueueDepth, AtomicInteger::get)
                .description("Batch completion tasks queued behind the completion executor. Once "
                        + "this reaches the queue capacity the rejection handler runs the task on "
                        + "the calling thread, which stalls the bulk worker - see "
                        + "contest.submission.completion.caller_runs")
                .register(registry);

        this.meters = Meters.of(registry);
    }

    public void recordRejectedSubmission() {
        rejectedSubmissionCount.increment();
        meters.rejected().increment();
    }

    public void recordInFlight(int inFlight, int pendingCount) {
        currentInFlight.set(inFlight);
        maxInFlight.accumulate(inFlight);
        lastPendingAfter.set(pendingCount);
    }

    public void recordInFlightLimit(int limit) {
        inFlightLimit.set(limit);
    }

    public void recordSuccess(int chunkSize, long elapsedMillis, int pendingBefore, int pendingAfter, int activeWorkers) {
        recordChunk(chunkSize, elapsedMillis, pendingBefore, pendingAfter, activeWorkers);
        meters.submissionsWritten().increment(chunkSize);
    }

    public void recordFailure(int chunkSize, long elapsedMillis, int pendingBefore, int pendingAfter, int activeWorkers) {
        failedChunkCount.increment();
        recordChunk(chunkSize, elapsedMillis, pendingBefore, pendingAfter, activeWorkers);
        // Counted here rather than by correcting the success counter afterwards: a counter that
        // moves backwards between two scrapes reads as a process restart to Prometheus.
        meters.submissionsFailed().increment(chunkSize);
    }

    private void recordChunk(int chunkSize, long elapsedMillis, int pendingBefore, int pendingAfter, int activeWorkers) {
        chunkCount.increment();
        totalChunkElapsedMillis.add(elapsedMillis);
        totalSubmissionCount.add(chunkSize);
        maxChunkElapsedMillis.accumulate(elapsedMillis);
        maxChunkSize.accumulate(chunkSize);
        maxPendingBefore.accumulate(pendingBefore);
        maxPendingAfter.accumulate(pendingAfter);
        maxActiveWorkers.accumulate(activeWorkers);
        lastPendingAfter.set(pendingAfter);
        meters.chunk().record(elapsedMillis, TimeUnit.MILLISECONDS);
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
            meters.completionFailures().increment();
        }
        meters.completionQueueDelay().record(queueDelayMillis, TimeUnit.MILLISECONDS);
        meters.completionTask().record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordCompletionExecutorState(int queueDepth, int activeWorkers) {
        maxCompletionQueueDepth.accumulate(queueDepth);
        maxActiveCompletionWorkers.accumulate(activeWorkers);
        lastCompletionQueueDepth.set(queueDepth);
    }

    public void recordCompletionCallerRuns() {
        completionCallerRunsCount.increment();
        meters.completionCallerRuns().increment();
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
                completionCallerRunsCount.sum(),
                rejectedSubmissionCount.sum(),
                currentInFlight.get(),
                maxInFlight.get()
        );
    }

    /**
     * Clears the per-run snapshot only. The Prometheus meters are left alone on purpose: they are
     * read across runs and a counter that drops to zero is indistinguishable from a restart.
     */
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
        rejectedSubmissionCount.reset();
        currentInFlight.set(0);
        maxInFlight.reset();
    }

    private record Meters(Timer chunk,
                          Counter submissionsWritten,
                          Counter submissionsFailed,
                          Counter rejected,
                          Timer completionQueueDelay,
                          Timer completionTask,
                          Counter completionFailures,
                          Counter completionCallerRuns) {

        private static Meters of(MeterRegistry registry) {
            return new Meters(
                    Timer.builder("contest.submission.bulk.chunk")
                            .description("Time to persist one chunk of submissions and their judge "
                                    + "outbox rows, successes and failures alike")
                            .serviceLevelObjectives(CHUNK_BUCKETS)
                            .register(registry),
                    Counter.builder("contest.submission.bulk.submissions")
                            .tag("outcome", "success")
                            .description("Submissions committed to the database")
                            .register(registry),
                    Counter.builder("contest.submission.bulk.submissions")
                            .tag("outcome", "failure")
                            .description("Submissions whose chunk failed to persist")
                            .register(registry),
                    Counter.builder("contest.submission.rejected")
                            .description("Submissions shed at the admission ceiling and answered "
                                    + "with 503. The per-user rate limit answers 429 and is "
                                    + "counted by http.server.requests instead")
                            .register(registry),
                    Timer.builder("contest.submission.completion.queue.delay")
                            .description("Time a batch completion task waited in the completion "
                                    + "executor queue before a thread picked it up")
                            .serviceLevelObjectives(COMPLETION_BUCKETS)
                            .register(registry),
                    Timer.builder("contest.submission.completion.task")
                            .description("Time to complete one batch of submission futures")
                            .serviceLevelObjectives(COMPLETION_BUCKETS)
                            .register(registry),
                    Counter.builder("contest.submission.completion.failures")
                            .description("Batch completion tasks that threw")
                            .register(registry),
                    Counter.builder("contest.submission.completion.caller_runs")
                            .description("Completion tasks run on the calling thread because the "
                                    + "executor queue was full. Non-zero means the completion "
                                    + "stage is back-pressuring the bulk DB workers")
                            .register(registry)
            );
        }
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
                           long completionCallerRunsCount,
                           long rejectedSubmissionCount,
                           int currentInFlight,
                           long maxInFlight) {
    }
}
