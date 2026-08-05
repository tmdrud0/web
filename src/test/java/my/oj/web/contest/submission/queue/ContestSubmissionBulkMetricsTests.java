package my.oj.web.contest.submission.queue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

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

    @Test
    void tracksAdmissionOccupancyAgainstItsCeiling() {
        metrics.recordInFlightLimit(800);
        metrics.recordInFlight(612, 37);

        assertThat(gauge("contest.submission.in_flight")).isEqualTo(612.0);
        assertThat(gauge("contest.submission.in_flight.limit")).isEqualTo(800.0);
        assertThat(gauge("contest.submission.bulk.queue.depth")).isEqualTo(37.0);
    }

    @Test
    void countsShedSubmissions() {
        metrics.recordRejectedSubmission();
        metrics.recordRejectedSubmission();

        assertThat(registry.get("contest.submission.rejected").counter().count()).isEqualTo(2.0);
    }

    @Test
    void separatesCompletionQueueDelayFromCompletionWork() {
        metrics.recordCompletion(100, 40L, 6L, 12, 4, false);

        assertThat(timer("contest.submission.completion.queue.delay").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(40.0);
        assertThat(timer("contest.submission.completion.task").totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(6.0);
        assertThat(gauge("contest.submission.completion.queue.depth")).isEqualTo(12.0);
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
                .contains("contest_submission_bulk_queue_depth")
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
