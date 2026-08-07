package my.oj.web.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the binder against a cgroup tree written into a temp directory. Nothing here
 * needs a container or a real cgroup mount.
 */
class CgroupResourceMetricsTests {

    /**
     * The snapshot cache compares against the empty snapshot's timestamp of 0, so a fake
     * clock has to start beyond CACHE_MILLIS or the first read is served from the empty
     * snapshot and no file is ever opened.
     */
    private static final long CLOCK_START = 10_000L;

    @TempDir
    Path cgroupRoot;

    private final AtomicLong clockMillis = new AtomicLong(CLOCK_START);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private CgroupResourceMetrics metrics;

    @BeforeEach
    void writeThrottledContainer() throws IOException {
        write("cpu.stat", """
                usage_usec 2000000
                user_usec 1500000
                system_usec 500000
                nr_periods 400
                nr_throttled 250
                throttled_usec 3000000
                """);
        write("cpu.max", "50000 100000\n");
        write("memory.current", "600000000\n");
        write("memory.stat", """
                anon 200000000
                file 350000000
                inactive_file 250000000
                active_file 100000000
                """);
        write("memory.events", """
                low 0
                high 0
                max 12
                oom 3
                oom_kill 2
                """);
        write("memory.max", "1073741824\n");
        metrics = new CgroupResourceMetrics(cgroupRoot, clockMillis::get);
    }

    @Test
    void readsQuotaAndThrottlingFromCpuFiles() {
        metrics.bindTo(registry);

        assertThat(gauge("cgroup.cpu.limit")).isEqualTo(0.5);
        assertThat(counter("cgroup.cpu.usage")).isEqualTo(2.0);
        assertThat(counter("cgroup.cpu.periods")).isEqualTo(400.0);
        assertThat(counter("cgroup.cpu.throttled.periods")).isEqualTo(250.0);
        assertThat(counter("cgroup.cpu.throttled.time")).isEqualTo(3.0);
    }

    @Test
    void reportsUnlimitedWhenCpuMaxCarriesNoQuota() throws IOException {
        write("cpu.max", "max 100000\n");
        metrics.bindTo(registry);

        assertThat(gauge("cgroup.cpu.limit")).isEqualTo(-1.0);
    }

    @Test
    void reportsUnlimitedWhenMemoryMaxIsTheWordMax() throws IOException {
        write("memory.max", "max\n");
        metrics.bindTo(registry);

        assertThat(gauge("cgroup.memory.limit")).isEqualTo(-1.0);
    }

    @Test
    void skipsNonNumericFieldsWithoutFailingTheRead() throws IOException {
        write("cpu.stat", """
                usage_usec 2000000
                nr_periods 400
                a_field_from_a_newer_kernel not-a-number
                nr_throttled 250
                """);
        metrics.bindTo(registry);

        assertThat(counter("cgroup.cpu.usage")).isEqualTo(2.0);
        assertThat(counter("cgroup.cpu.periods")).isEqualTo(400.0);
        assertThat(counter("cgroup.cpu.throttled.periods")).isEqualTo(250.0);
    }

    @Test
    void workingSetLeavesOutTheReclaimableFileCache() {
        metrics.bindTo(registry);

        assertThat(gauge("cgroup.memory.usage")).isEqualTo(600_000_000.0);
        assertThat(gauge("cgroup.memory.working_set")).isEqualTo(350_000_000.0);
        assertThat(gauge("cgroup.memory.limit")).isEqualTo(1_073_741_824.0);
    }

    @Test
    void workingSetFallsBackToTheFullChargeWithoutMemoryStat() throws IOException {
        Files.delete(cgroupRoot.resolve("memory.stat"));
        metrics.bindTo(registry);

        assertThat(gauge("cgroup.memory.working_set")).isEqualTo(600_000_000.0);
    }

    @Test
    void countsOomKillsFromMemoryEvents() {
        metrics.bindTo(registry);

        assertThat(counter("cgroup.memory.oom_kills")).isEqualTo(2.0);
    }

    @Test
    void rereadsOnlyAfterTheCacheWindowPasses() throws IOException {
        metrics.bindTo(registry);
        assertThat(gauge("cgroup.memory.usage")).isEqualTo(600_000_000.0);

        write("memory.current", "700000000\n");
        assertThat(gauge("cgroup.memory.usage"))
                .as("a read inside the cache window must not touch the filesystem")
                .isEqualTo(600_000_000.0);

        advancePastCacheWindow();
        assertThat(gauge("cgroup.memory.usage")).isEqualTo(700_000_000.0);
    }

    @Test
    void countersHoldTheirValueWhenTheCgroupFilesGoAway() throws IOException {
        metrics.bindTo(registry);
        assertThat(counter("cgroup.cpu.usage")).isEqualTo(2.0);

        Files.delete(cgroupRoot.resolve("cpu.stat"));
        advancePastCacheWindow();

        assertThat(counter("cgroup.cpu.usage"))
                .as("a counter dropping to zero reads as a process restart and resets rate()")
                .isEqualTo(2.0);
        assertThat(counter("cgroup.cpu.throttled.time")).isEqualTo(3.0);
    }

    @Test
    void oomKillsHoldTheirValueWhenTheFieldDisappearsFromAReadableFile() throws IOException {
        metrics.bindTo(registry);
        assertThat(counter("cgroup.memory.oom_kills")).isEqualTo(2.0);

        // memory.events still reads, it just no longer carries oom_kill. memory.current
        // moves at the same time to prove the snapshot really was re-read.
        write("memory.events", "low 0\nhigh 0\n");
        write("memory.current", "700000000\n");
        advancePastCacheWindow();

        assertThat(gauge("cgroup.memory.usage")).isEqualTo(700_000_000.0);
        assertThat(counter("cgroup.memory.oom_kills")).isEqualTo(2.0);
    }

    @Test
    void oomKillsHoldTheirValueWhenMemoryEventsIsMissingEntirely() throws IOException {
        metrics.bindTo(registry);
        assertThat(counter("cgroup.memory.oom_kills")).isEqualTo(2.0);

        Files.delete(cgroupRoot.resolve("memory.events"));
        write("memory.current", "700000000\n");
        advancePastCacheWindow();

        assertThat(gauge("cgroup.memory.usage")).isEqualTo(700_000_000.0);
        assertThat(counter("cgroup.memory.oom_kills")).isEqualTo(2.0);
    }

    /**
     * The Micrometer meter id and the exported series name are not the same string, and
     * observability/grafana/dashboards/oj-bottleneck.json queries the exported one.
     */
    @Test
    void exportsTheSeriesNamesTheDashboardQueries() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics.bindTo(prometheus);

        assertThat(prometheus.scrape())
                .contains("cgroup_memory_working_set_bytes")
                .contains("cgroup_memory_oom_kills_total")
                .contains("cgroup_memory_limit_bytes")
                .contains("cgroup_cpu_periods_total")
                .contains("cgroup_cpu_throttled_periods_total");
    }

    @Test
    void registersNothingWhenCpuStatIsAbsent(@TempDir Path cgroupV1Host) {
        new CgroupResourceMetrics(cgroupV1Host).bindTo(registry);

        assertThat(registry.getMeters()).isEmpty();
    }

    private void advancePastCacheWindow() {
        clockMillis.addAndGet(2_000L);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).functionCounter().count();
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(cgroupRoot.resolve(name), content);
    }
}
