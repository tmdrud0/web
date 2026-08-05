package my.oj.web.contest.submission.queue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Prometheus half of the class. The snapshot half is exercised through the writer in
 * {@link ContestSubmissionBulkWriterTests}.
 */
class ContestSubmissionBulkMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();

    @BeforeEach
    void bind() {
        metrics.bindTo(registry);
    }

    @Test
    void recordsChunkLatencyIntoHistogramBuckets() {
        metrics.recordSuccess(100, 30L, 200, 100, 4);
        metrics.recordSuccess(100, 700L, 100, 0, 4);

        HistogramSnapshot histogram = timer("contest.submission.bulk.chunk").takeSnapshot();
        assertThat(histogram.count()).isEqualTo(2);
        // Buckets are what makes a percentile computable over web-1 and web-2 together; a
        // per-instance maximum could not be combined at all.
        assertThat(bucketCount(histogram, 0.05)).isEqualTo(1.0);
        assertThat(bucketCount(histogram, 1.0)).isEqualTo(2.0);
    }

    @Test
    void separatesWrittenSubmissionsFromFailedOnes() {
        metrics.recordSuccess(100, 12L, 100, 0, 4);
        metrics.recordFailure(40, 9L, 40, 0, 4);

        assertThat(counter("contest.submission.bulk.submissions", "outcome", "success")).isEqualTo(100.0);
        assertThat(counter("contest.submission.bulk.submissions", "outcome", "failure")).isEqualTo(40.0);
    }

    /**
     * A failed chunk still spent the time it spent. Excluding it would flatter the latency
     * histogram exactly when the pipeline is in trouble.
     */
    @Test
    void timesFailedChunksAlongsideSuccessfulOnes() {
        metrics.recordFailure(40, 9L, 40, 0, 4);

        assertThat(timer("contest.submission.bulk.chunk").count()).isEqualTo(1);
    }

    /**
     * The point of the whole gauge set: a scrape reads the live objects. Registering the value
     * that was true at bind time, or caching one taken when a chunk last finished, would leave
     * every depth frozen through the event worth watching.
     */
    @Test
    void gaugesReadTheirSourcesAtScrapeTimeRatherThanAtBindTime() {
        AtomicInteger pending = new AtomicInteger(0);
        AtomicInteger activeWorkers = new AtomicInteger(0);
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger completionQueue = new AtomicInteger(0);
        AtomicInteger completionActive = new AtomicInteger(0);
        metrics.bindSubmissionQueue(pending::get, activeWorkers::get, inFlight::get, 4, 800);
        metrics.bindCompletionExecutor(completionQueue::get, completionActive::get, 64, 4);

        assertThat(gauge("contest.submission.in_flight")).isZero();

        pending.set(37);
        activeWorkers.set(4);
        inFlight.set(612);
        completionQueue.set(19);
        completionActive.set(3);

        assertThat(gauge("contest.submission.in_flight")).isEqualTo(612.0);
        assertThat(gauge("contest.submission.bulk.queue.depth")).isEqualTo(37.0);
        assertThat(gauge("contest.submission.bulk.active.workers")).isEqualTo(4.0);
        assertThat(gauge("contest.submission.completion.queue.depth")).isEqualTo(19.0);
        assertThat(gauge("contest.submission.completion.active")).isEqualTo(3.0);
    }

    /** Every depth ships its ceiling so a panel is a ratio and no dashboard hardcodes a config value. */
    @Test
    void publishesTheConfiguredCeilingBesideEveryDepth() {
        metrics.bindSubmissionQueue(() -> 0, () -> 0, () -> 0, 4, 800);
        metrics.bindCompletionExecutor(() -> 0, () -> 0, 64, 4);

        assertThat(gauge("contest.submission.in_flight.limit")).isEqualTo(800.0);
        assertThat(gauge("contest.submission.bulk.workers.limit")).isEqualTo(4.0);
        assertThat(gauge("contest.submission.completion.queue.capacity")).isEqualTo(64.0);
        assertThat(gauge("contest.submission.completion.threads")).isEqualTo(4.0);
    }

    /**
     * Binding happens in the writer's and the dispatcher's constructors, which Spring may run
     * either side of the registry being created. A gauge registered before the source arrives
     * has to still find it.
     */
    @Test
    void picksUpASourceBoundAfterTheRegistry() {
        assertThat(gauge("contest.submission.in_flight.limit")).isZero();

        metrics.bindSubmissionQueue(() -> 7, () -> 0, () -> 0, 4, 800);

        assertThat(gauge("contest.submission.in_flight.limit")).isEqualTo(800.0);
        assertThat(gauge("contest.submission.bulk.queue.depth")).isEqualTo(7.0);
    }

    @Test
    void countsShedSubmissions() {
        metrics.recordRejectedSubmission();
        metrics.recordRejectedSubmission();

        assertThat(registry.get("contest.submission.rejected").counter().count()).isEqualTo(2.0);
    }

    /**
     * Queueing and work are separate meters because they fail for different reasons: delay grows
     * when the executor is behind, elapsed grows when completing a batch of futures got slower.
     * The executor depth that arrives on the same call goes only to the snapshot's accumulator -
     * the gauge for it reads the executor itself.
     */
    @Test
    void separatesCompletionQueueDelayFromCompletionWork() {
        metrics.recordCompletion(100, 40L, 6L, 12, 4, false);

        assertThat(timer("contest.submission.completion.queue.delay").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(40.0);
        assertThat(timer("contest.submission.completion.task").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(6.0);
        assertThat(metrics.snapshot().maxCompletionQueueDepth()).isEqualTo(12);
        assertThat(registry.get("contest.submission.completion.failures").counter().count()).isZero();
    }

    @Test
    void countsCompletionFailuresAndCallerRuns() {
        metrics.recordCompletion(100, 0L, 3L, 64, 4, true);
        metrics.recordCompletionCallerRuns();

        assertThat(registry.get("contest.submission.completion.failures").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("contest.submission.completion.caller_runs").counter().count()).isEqualTo(1.0);
    }

    /**
     * The perf endpoint resets the snapshot between load runs. Prometheus reads across runs, and a
     * counter that falls back to zero is indistinguishable from a process restart.
     */
    @Test
    void resetClearsTheSnapshotAndLeavesTheMetersAlone() {
        metrics.recordSuccess(100, 12L, 100, 0, 4);
        metrics.recordRejectedSubmission();

        metrics.reset();

        assertThat(metrics.snapshot().chunkCount()).isZero();
        assertThat(metrics.snapshot().rejectedSubmissionCount()).isZero();
        assertThat(timer("contest.submission.bulk.chunk").count()).isEqualTo(1);
        assertThat(registry.get("contest.submission.rejected").counter().count()).isEqualTo(1.0);
    }

    /** Unit tests and any other unbound use must not blow up on a recording. */
    @Test
    void recordsWithoutARegistryUntilItIsBound() {
        ContestSubmissionBulkMetrics unbound = new ContestSubmissionBulkMetrics();

        unbound.recordSuccess(100, 12L, 100, 0, 4);
        unbound.recordCompletion(100, 1L, 1L, 0, 4, true);
        unbound.recordRejectedSubmission();

        assertThat(unbound.snapshot().totalSubmissionCount()).isEqualTo(100);
    }

    /**
     * The Micrometer meter id and the exported series name are not the same string, and
     * observability/grafana/dashboards/oj-bottleneck.json queries the exported one.
     */
    @Test
    void exportsTheSeriesNamesTheDashboardQueries() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ContestSubmissionBulkMetrics exported = new ContestSubmissionBulkMetrics();
        exported.bindTo(prometheus);
        exported.recordSuccess(100, 12L, 100, 0, 4);
        exported.recordCompletion(100, 1L, 1L, 0, 4, false);

        assertThat(prometheus.scrape())
                .contains("contest_submission_bulk_chunk_seconds_bucket")
                .contains("contest_submission_bulk_submissions_total")
                .contains("contest_submission_rejected_total")
                .contains("contest_submission_in_flight")
                .contains("contest_submission_in_flight_limit")
                .contains("contest_submission_bulk_queue_depth")
                .contains("contest_submission_bulk_active_workers")
                .contains("contest_submission_bulk_workers_limit")
                .contains("contest_submission_completion_queue_depth")
                .contains("contest_submission_completion_queue_capacity")
                .contains("contest_submission_completion_active")
                .contains("contest_submission_completion_threads")
                .contains("contest_submission_completion_queue_delay_seconds_bucket")
                .contains("contest_submission_completion_caller_runs_total");
    }

    private Timer timer(String name) {
        return registry.get(name).timer();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    private static double bucketCount(HistogramSnapshot histogram, double seconds) {
        for (var bucket : histogram.histogramCounts()) {
            if (bucket.bucket(TimeUnit.SECONDS) == seconds) {
                return bucket.count();
            }
        }
        throw new AssertionError("No histogram bucket at " + seconds + "s");
    }
}
