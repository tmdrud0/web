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
import java.util.function.LongSupplier;

/**
 * Publishes this container's cgroup v2 CPU quota, CPU throttling counters, memory usage
 * against its limit and OOM kill count.
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
 *
 * <p>Memory headroom is reported as a working set rather than as {@code memory.current},
 * which counts reclaimable page cache and therefore pins to the limit on any container
 * doing file I/O. The {@code oom_kill} counter beside it is the kernel's own tally rather
 * than an inference, but it only reaches kills this process outlives: when the victim is
 * the JVM the container ends with it, the increment falls after the last scrape and is
 * never collected, and the replacement container starts a fresh cgroup at zero. A zero
 * there is therefore not evidence that no OOM happened - process restarts are what catch
 * that case.
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
    private final Path memoryStat;
    private final Path memoryEvents;
    private final Path memoryMax;
    private final LongSupplier clockMillis;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>(Snapshot.EMPTY);

    public CgroupResourceMetrics() {
        this(DEFAULT_CGROUP_ROOT);
    }

    CgroupResourceMetrics(Path cgroupRoot) {
        this(cgroupRoot, System::currentTimeMillis);
    }

    CgroupResourceMetrics(Path cgroupRoot, LongSupplier clockMillis) {
        this.cpuStat = cgroupRoot.resolve("cpu.stat");
        this.cpuMax = cgroupRoot.resolve("cpu.max");
        this.memoryCurrent = cgroupRoot.resolve("memory.current");
        this.memoryStat = cgroupRoot.resolve("memory.stat");
        this.memoryEvents = cgroupRoot.resolve("memory.events");
        this.memoryMax = cgroupRoot.resolve("memory.max");
        this.clockMillis = clockMillis;
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
                .description("Memory charged to this container's cgroup, page cache included and "
                        + "nothing deducted for what the kernel can reclaim. Compare against the "
                        + "limit only through cgroup.memory.working_set")
                .register(registry);
        Gauge.builder("cgroup.memory.working_set", this, self -> self.snapshot().memoryWorkingSetBytes())
                .baseUnit("bytes")
                .description("memory.current minus inactive_file, the definition cAdvisor, docker "
                        + "stats and kubelet all report. The kernel reclaims that cache before it "
                        + "kills anything, so this is the part a limit increase has to cover")
                .register(registry);
        Gauge.builder("cgroup.memory.limit", this, self -> self.snapshot().memoryLimitBytes())
                .baseUnit("bytes")
                .description("Memory limit for this container. -1 when unlimited")
                .register(registry);
        FunctionCounter.builder("cgroup.memory.oom_kills", this, self -> self.snapshot().oomKills())
                .description("Processes the kernel OOM killer has killed in this cgroup, from "
                        + "memory.events. Only counts kills this process outlives: a kill of the "
                        + "JVM itself ends the container before the increment can be scraped, and "
                        + "the replacement counts from zero in a new cgroup")
                .register(registry);
    }

    private Snapshot snapshot() {
        Snapshot current = cached.get();
        long now = clockMillis.getAsLong();
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
            Map<String, Long> cpu = readKeyedLongs(cpuStat);
            Map<String, Long> memory = readKeyedLongsQuietly(memoryStat);
            Map<String, Long> events = readKeyedLongsQuietly(memoryEvents);
            double usedBytes = readLongOrDefault(memoryCurrent, 0L);
            return new Snapshot(
                    now,
                    counterSeconds(cpu, "usage_usec", previous.usedSeconds()),
                    counter(cpu, "nr_periods", previous.periods()),
                    counter(cpu, "nr_throttled", previous.throttledPeriods()),
                    counterSeconds(cpu, "throttled_usec", previous.throttledSeconds()),
                    readCpuLimitCores(),
                    usedBytes,
                    workingSetBytes(usedBytes, memory),
                    readMemoryLimitBytes(),
                    counter(events, "oom_kill", previous.oomKills()));
        } catch (IOException | RuntimeException e) {
            log.debug("cgroup read failed; keeping previous values", e);
            return previous.readAt(now);
        }
    }

    /**
     * A file can read cleanly and still not carry the field: cpu.stat omits the throttling
     * counters when no quota is set, and memory.events is absent on kernels without the
     * memory controller's event interface. Defaulting to zero there would step around the
     * carry-forward contract that the read failure path exists to honour.
     */
    private static double counter(Map<String, Long> stat, String field, double previous) {
        Long value = stat.get(field);
        return value == null ? previous : value;
    }

    private static double counterSeconds(Map<String, Long> stat, String field, double previous) {
        Long micros = stat.get(field);
        return micros == null ? previous : micros / 1_000_000.0;
    }

    /**
     * inactive_file is what the kernel drops first under pressure, so removing it leaves
     * roughly the memory a limit increase would actually have to cover. memory.current on
     * its own sticks to the limit on any container doing file I/O and never falls back.
     */
    private static double workingSetBytes(double usedBytes, Map<String, Long> memoryStat) {
        return Math.max(0.0, usedBytes - memoryStat.getOrDefault("inactive_file", 0L));
    }

    /** cpu.stat, memory.stat and memory.events share the same "key value" line format. */
    private Map<String, Long> readKeyedLongs(Path path) throws IOException {
        Map<String, Long> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            int separator = line.indexOf(' ');
            if (separator <= 0) {
                continue;
            }
            try {
                values.put(line.substring(0, separator), Long.parseLong(line.substring(separator + 1).trim()));
            } catch (NumberFormatException ignored) {
                // These files gain fields across kernel versions. Skip anything non-numeric.
            }
        }
        return values;
    }

    /**
     * For the optional files. Failing the whole read would discard the CPU values that did
     * come back; an empty map hands each field to its carry-forward instead.
     */
    private Map<String, Long> readKeyedLongsQuietly(Path path) {
        try {
            return readKeyedLongs(path);
        } catch (IOException e) {
            return Map.of();
        }
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
                            double memoryWorkingSetBytes,
                            double memoryLimitBytes,
                            double oomKills) {

        private static final Snapshot EMPTY =
                new Snapshot(0L, 0.0, 0.0, 0.0, 0.0, UNLIMITED, 0.0, 0.0, UNLIMITED, 0.0);

        private Snapshot readAt(long now) {
            return new Snapshot(now, usedSeconds, periods, throttledPeriods, throttledSeconds,
                    limitCores, memoryUsedBytes, memoryWorkingSetBytes, memoryLimitBytes, oomKills);
        }
    }
}
