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
import java.util.function.IntSupplier;

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
 * gauges that read the live objects, so a peak comes from {@code max_over_time()} at query time.
 * {@link #reset()} deliberately does not touch them - a counter falling back to zero reads as a
 * process restart and resets {@code rate()}.
 *
 * <p>The {@code max*} accumulators exist because a snapshot read once per run cannot observe a
 * peak any other way. A five-second scrape can, so none of them is exported and none is mirrored
 * into a field for the gauges to read: the gauges call into the writer and the completion
 * executor at scrape time. The cost is that a spike shorter than the scrape interval is missed by
 * the gauges while the accumulators still catch it, which is accepted because a series that sums
 * across instances is worth more in operation than a per-JVM maximum that does not. Recovering a
 * sub-scrape peak in aggregatable form would mean publishing the record-time value as a
 * DistributionSummary; nothing needs that yet.
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

    /**
     * The live objects the gauges read. Volatile because {@link #bindTo(MeterRegistry)} and the
     * two bind calls below race: Spring binds MeterBinder beans when it creates the registry,
     * which may be before or after the writer and the dispatcher are constructed. Reading these
     * inside the gauge lambdas rather than at registration time makes the order irrelevant.
     */
    private volatile SubmissionQueueState submissionQueue = SubmissionQueueState.UNBOUND;
    private volatile CompletionExecutorState completionExecutor = CompletionExecutorState.UNBOUND;

    /**
     * A composite registry with no children discards every recording, so the meters exist before
     * Spring binds this to the real registry and the record methods need no null check. Unit tests
     * that construct this class directly are never bound and take the same path.
     */
    private volatile Meters meters = Meters.of(new CompositeMeterRegistry());

    /**
     * Hands the bulk writer's live state to the gauges. Suppliers rather than values: a gauge that
     * published what was true when a chunk last finished would sit frozen through exactly the
     * event worth seeing, a queue growing while no worker completes.
     *
     * @param inFlight admitted but not yet committed, so {@code inFlightLimit - inFlight} is the
     *                 remaining admission headroom before submissions are shed with 503
     */
    public void bindSubmissionQueue(IntSupplier pendingCount,
                                    IntSupplier activeWorkers,
                                    IntSupplier inFlight,
                                    int workerLimit,
                                    int inFlightLimit) {
        this.submissionQueue =
                new SubmissionQueueState(pendingCount, activeWorkers, inFlight, workerLimit, inFlightLimit);
    }

    /**
     * Hands the completion executor's live state to the gauges. {@code ThreadPoolExecutor} already
     * tracks both numbers, so nothing needs to be mirrored into a field to publish them.
     */
    public void bindCompletionExecutor(IntSupplier queueDepth,
                                       IntSupplier activeThreads,
                                       int queueCapacity,
                                       int threadCount) {
        this.completionExecutor =
                new CompletionExecutorState(queueDepth, activeThreads, queueCapacity, threadCount);
    }

    /**
     * Every depth gauge here ships the configured ceiling beside it, so a panel is a ratio and no
     * dashboard has to hardcode a value from application.properties. The ceilings are constants
     * between deploys; publishing them as gauges is what keeps a config change visible.
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("contest.submission.in_flight", this, self -> self.submissionQueue.inFlight().getAsInt())
                .description("Submissions admitted but not yet committed to the database. Compare "
                        + "against contest.submission.in_flight.limit; the difference is the "
                        + "admission headroom before requests are shed with 503")
                .register(registry);
        Gauge.builder("contest.submission.in_flight.limit", this, self -> self.submissionQueue.inFlightLimit())
                .description("Configured max-in-flight admission ceiling for this instance")
                .register(registry);
        Gauge.builder("contest.submission.bulk.queue.depth", this,
                        self -> self.submissionQueue.pendingCount().getAsInt())
                .description("Submissions waiting for a bulk worker to pick them up. An instant "
                        + "value rather than a peak: take the peak with max_over_time(), which a "
                        + "per-instance maximum could not give across web-1 and web-2")
                .register(registry);
        Gauge.builder("contest.submission.bulk.active.workers", this,
                        self -> self.submissionQueue.activeWorkers().getAsInt())
                .description("Bulk workers currently persisting a chunk. At the worker limit with "
                        + "a non-empty queue, the DB write stage is the constraint")
                .register(registry);
        Gauge.builder("contest.submission.bulk.workers.limit", this, self -> self.submissionQueue.workerLimit())
                .description("Configured bulk worker count for this instance")
                .register(registry);
        Gauge.builder("contest.submission.completion.queue.depth", this,
                        self -> self.completionExecutor.queueDepth().getAsInt())
                .description("Batch completion tasks queued behind the completion executor. Once "
                        + "this reaches the queue capacity the rejection handler runs the task on "
                        + "the calling thread, which stalls the bulk worker - see "
                        + "contest.submission.completion.caller_runs")
                .register(registry);
        Gauge.builder("contest.submission.completion.queue.capacity", this,
                        self -> self.completionExecutor.queueCapacity())
                .description("Configured completion executor queue capacity for this instance")
                .register(registry);
        Gauge.builder("contest.submission.completion.active", this,
                        self -> self.completionExecutor.activeThreads().getAsInt())
                .description("Completion executor threads currently completing submission futures")
                .register(registry);
        Gauge.builder("contest.submission.completion.threads", this,
                        self -> self.completionExecutor.threadCount())
                .description("Configured completion executor thread count for this instance")
                .register(registry);

        this.meters = Meters.of(registry);
    }

    public void recordRejectedSubmission() {
        rejectedSubmissionCount.increment();
        meters.rejected().increment();
    }

    public void recordInFlight(int inFlight) {
        currentInFlight.set(inFlight);
        maxInFlight.accumulate(inFlight);
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

    /** @param inFlightLimit and {@code workerLimit} are fixed for the life of the process */
    private record SubmissionQueueState(IntSupplier pendingCount,
                                        IntSupplier activeWorkers,
                                        IntSupplier inFlight,
                                        int workerLimit,
                                        int inFlightLimit) {

        /**
         * Reads zero everywhere until the writer wires itself. Only reachable in a context that
         * registers this bean without a writer, which is a unit test rather than a running role.
         */
        private static final SubmissionQueueState UNBOUND =
                new SubmissionQueueState(() -> 0, () -> 0, () -> 0, 0, 0);
    }

    private record CompletionExecutorState(IntSupplier queueDepth,
                                           IntSupplier activeThreads,
                                           int queueCapacity,
                                           int threadCount) {

        private static final CompletionExecutorState UNBOUND =
                new CompletionExecutorState(() -> 0, () -> 0, 0, 0);
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
