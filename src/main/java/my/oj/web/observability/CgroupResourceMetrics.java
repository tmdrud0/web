package my.oj.web.observability;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes this container's cgroup v2 CPU quota, CPU throttling counters and memory
 * usage against its limit.
 *
 * <p>cAdvisor is the usual source for these. It cannot label containers on Docker
 * Desktop: it resolves each container's read-write layer under
 * {@code /var/lib/docker/image/<driver>/layerdb/mounts}, which the containerd image
 * store never creates, so every container registration fails and only the root cgroup
 * is reported. Reading the cgroup from inside the JVM removes the host dependency and
 * carries the same {@code role} tag as every other application metric.
 *
 * <p>Throttling is the first thing to check when reading any measurement taken under
 * the fixed resource baseline: a container sitting at its CPU quota invalidates
 * conclusions about application-level bottlenecks.
 */
@Component
public class CgroupResourceMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(CgroupResourceMetrics.class);
    private static final Path DEFAULT_CGROUP_ROOT = Path.of("/sys/fs/cgroup");
    private static final long CACHE_MILLIS = 1000L;
    private static final double UNLIMITED = -1.0;

    private final Path cpuStat;
    private final Path cpuMax;
    private final Path memoryCurrent;
    private final Path memoryMax;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>(Snapshot.EMPTY);

    public CgroupResourceMetrics() {
        this(DEFAULT_CGROUP_ROOT);
    }

    CgroupResourceMetrics(Path cgroupRoot) {
        this.cpuStat = cgroupRoot.resolve("cpu.stat");
        this.cpuMax = cgroupRoot.resolve("cpu.max");
        this.memoryCurrent = cgroupRoot.resolve("memory.current");
        this.memoryMax = cgroupRoot.resolve("memory.max");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (!Files.isReadable(cpuStat)) {
            log.info("cgroup v2 not readable at {}; container resource metrics are disabled. "
                    + "This is expected outside a container.", cpuStat);
            return;
        }

        FunctionCounter.builder("cgroup.cpu.usage", this, self -> self.snapshot().usedSeconds())
                .baseUnit("seconds")
                .description("CPU time consumed by this container")
                .register(registry);
        FunctionCounter.builder("cgroup.cpu.periods", this, self -> self.snapshot().periods())
                .description("CFS scheduling periods elapsed for this container")
                .register(registry);
        FunctionCounter.builder("cgroup.cpu.throttled.periods", this, self -> self.snapshot().throttledPeriods())
                .description("CFS periods in which this container was denied runnable time")
                .register(registry);
        FunctionCounter.builder("cgroup.cpu.throttled.time", this, self -> self.snapshot().throttledSeconds())
                .baseUnit("seconds")
                .description("Runnable time denied to this container by its CPU quota")
                .register(registry);
        Gauge.builder("cgroup.cpu.limit", this, self -> self.snapshot().limitCores())
                .baseUnit("cores")
                .description("CPU cores this container may use, from cpu.max. -1 when unlimited")
                .register(registry);
        Gauge.builder("cgroup.memory.usage", this, self -> self.snapshot().memoryUsedBytes())
                .baseUnit("bytes")
                .description("Current memory charged to this container's cgroup")
                .register(registry);
        Gauge.builder("cgroup.memory.limit", this, self -> self.snapshot().memoryLimitBytes())
                .baseUnit("bytes")
                .description("Memory limit for this container. -1 when unlimited")
                .register(registry);
    }

    private Snapshot snapshot() {
        Snapshot current = cached.get();
        long now = System.currentTimeMillis();
        if (now - current.readAtMillis() < CACHE_MILLIS) {
            return current;
        }
        Snapshot fresh = read(now, current);
        cached.set(fresh);
        return fresh;
    }

    /**
     * Carries the previous counter values forward on a read failure. Counters must not
     * fall back to zero, or Prometheus reads the drop as a process restart.
     */
    private Snapshot read(long now, Snapshot previous) {
        try {
            Map<String, Long> stat = readCpuStat();
            return new Snapshot(
                    now,
                    stat.getOrDefault("usage_usec", 0L) / 1_000_000.0,
                    stat.getOrDefault("nr_periods", 0L),
                    stat.getOrDefault("nr_throttled", 0L),
                    stat.getOrDefault("throttled_usec", 0L) / 1_000_000.0,
                    readCpuLimitCores(),
                    readLongOrDefault(memoryCurrent, 0L),
                    readMemoryLimitBytes());
        } catch (IOException | RuntimeException e) {
            log.debug("cgroup read failed; keeping previous values", e);
            return previous.readAt(now);
        }
    }

    private Map<String, Long> readCpuStat() throws IOException {
        Map<String, Long> values = new HashMap<>();
        for (String line : Files.readAllLines(cpuStat)) {
            int separator = line.indexOf(' ');
            if (separator <= 0) {
                continue;
            }
            try {
                values.put(line.substring(0, separator), Long.parseLong(line.substring(separator + 1).trim()));
            } catch (NumberFormatException ignored) {
                // cpu.stat gains fields across kernel versions. Skip anything non-numeric.
            }
        }
        return values;
    }

    /** {@code cpu.max} holds "quota period", or "max period" when no quota is set. */
    private double readCpuLimitCores() throws IOException {
        String[] parts = Files.readString(cpuMax).trim().split("\\s+");
        if (parts.length < 2 || "max".equals(parts[0])) {
            return UNLIMITED;
        }
        double period = Double.parseDouble(parts[1]);
        return period == 0.0 ? UNLIMITED : Double.parseDouble(parts[0]) / period;
    }

    private double readMemoryLimitBytes() {
        long limit = readLongOrDefault(memoryMax, -1L);
        return limit < 0 ? UNLIMITED : limit;
    }

    /** Returns the fallback for "max" and for any unreadable or malformed file. */
    private long readLongOrDefault(Path path, long fallback) {
        try {
            return Long.parseLong(Files.readString(path).trim());
        } catch (IOException | NumberFormatException e) {
            return fallback;
        }
    }

    private record Snapshot(long readAtMillis,
                            double usedSeconds,
                            double periods,
                            double throttledPeriods,
                            double throttledSeconds,
                            double limitCores,
                            double memoryUsedBytes,
                            double memoryLimitBytes) {

        private static final Snapshot EMPTY = new Snapshot(0L, 0.0, 0.0, 0.0, 0.0, UNLIMITED, 0.0, UNLIMITED);

        private Snapshot readAt(long now) {
            return new Snapshot(now, usedSeconds, periods, throttledPeriods, throttledSeconds,
                    limitCores, memoryUsedBytes, memoryLimitBytes);
        }
    }
}
