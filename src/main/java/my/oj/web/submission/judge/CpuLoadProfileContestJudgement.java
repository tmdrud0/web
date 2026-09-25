package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spends the configured judge duration in on-thread computation instead of {@link Thread#sleep}.
 *
 * <p>{@link LatencyProfileContestJudgement} blocks a consumer thread without using a core, which
 * hides one real cost: a real judge burns CPU on the same core the dispatcher (MySQL claim/outbox
 * completion/JDBC, or RabbitMQ ack/AMQP/result save) and the JVM's own GC run on. This class
 * reproduces that competition using the identical seed, slow-ratio and classification as the sleep
 * judge &mdash; the draw comes from the same {@link ContestJudgeLatencyProperties#deterministicDraw},
 * so a given submission is "slow" or "fast" the same way under either mode; only how the time is
 * spent differs.
 *
 * <p>The stopping condition is on-thread CPU time from {@link ThreadMXBean}, not wall-clock time.
 * Wall-clock would let a thread that lost the core to something else "finish" a judgement without
 * actually having spent the CPU, which would defeat the point of this variant (no CPU contention
 * would ever show up as a longer judgement). Thread CPU time accounting must be supported and
 * enabled, or construction fails so the node refuses to start rather than silently spinning on an
 * unmeasured loop.
 *
 * <p>Exactly one of this class and {@link LatencyProfileContestJudgement} is active at a time,
 * selected by {@code contest.submission.judge.latency.mode}; see that class's doc for the full
 * three-way switch with {@link ContestProvisionalJudgement}.
 */
@Component
@ConditionalOnExpression("${contest.submission.judge.latency.enabled:false} "
        + "and '${contest.submission.judge.latency.mode:sleep}'.equalsIgnoreCase('cpu')")
@EnableConfigurationProperties(ContestJudgeLatencyProperties.class)
public class CpuLoadProfileContestJudgement implements ContestSubmissionJudgement {

    /**
     * Iterations per chunk between CPU-time checks. {@code ThreadMXBean.getCurrentThreadCpuTime()}
     * is itself not free (backed by a native/syscall read), so checking every iteration would make
     * the check a large fraction of the cost; too large a chunk overshoots the target instead. This
     * chunk runs in roughly tens of microseconds on a typical core, small next to the 10ms/2000ms
     * targets it is measured against.
     */
    private static final int CHUNK_ITERATIONS = 50_000;

    private final ContestJudgeLatencyProperties properties;
    private final ContestJudgeLatencyClassMetrics latencyClassMetrics;
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    /** Consumes the busy loop's result so the JIT cannot prove it dead and elide it. */
    private volatile long sink;

    public CpuLoadProfileContestJudgement(ContestJudgeLatencyProperties properties,
                                          ContestJudgeLatencyClassMetrics latencyClassMetrics) {
        this.properties = properties;
        this.latencyClassMetrics = latencyClassMetrics;
        if (!threadMXBean.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException(
                    "contest.submission.judge.latency.mode=cpu requires JVM thread CPU time "
                            + "accounting (ThreadMXBean.isCurrentThreadCpuTimeSupported()=false); "
                            + "refusing to start rather than silently spinning on wall-clock time");
        }
        if (threadMXBean.isThreadCpuTimeSupported() && !threadMXBean.isThreadCpuTimeEnabled()) {
            threadMXBean.setThreadCpuTimeEnabled(true);
        }
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            throw new IllegalStateException(
                    "contest.submission.judge.latency.mode=cpu could not enable ThreadMXBean CPU "
                            + "time accounting");
        }
    }

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        double draw = properties.seed() == null
                ? ThreadLocalRandom.current().nextDouble()
                : properties.deterministicDraw(submission.getSubmissionId(), submission.getCode());
        boolean slow = properties.isSlow(draw);
        String latencyClass = slow
                ? ContestJudgeLatencyClassMetrics.SLOW
                : ContestJudgeLatencyClassMetrics.FAST;
        long targetCpuNanos =
                (slow ? properties.effectiveSlowMillis() : properties.effectiveBaseMillis())
                        * 1_000_000L;

        long wallStarted = System.nanoTime();
        long cpuStarted = threadMXBean.getCurrentThreadCpuTime();
        burnCpu(targetCpuNanos, cpuStarted);
        long cpuElapsed = threadMXBean.getCurrentThreadCpuTime() - cpuStarted;
        latencyClassMetrics.record(latencyClass, System.nanoTime() - wallStarted);
        latencyClassMetrics.recordCpu(latencyClass, cpuElapsed);
        return SubmissionResult.PARTIAL_ACCEPTED;
    }

    /**
     * Busy-waits until {@code targetCpuNanos} of this thread's own CPU time has elapsed since
     * {@code cpuStarted}, checked once per {@link #CHUNK_ITERATIONS}-iteration chunk.
     */
    private void burnCpu(long targetCpuNanos, long cpuStarted) {
        long accumulator = cpuStarted;
        while (threadMXBean.getCurrentThreadCpuTime() - cpuStarted < targetCpuNanos) {
            for (int i = 0; i < CHUNK_ITERATIONS; i++) {
                accumulator = mix(accumulator, i);
            }
            // Publish so the JIT cannot determine accumulator/the loop above are dead.
            sink = accumulator;
        }
    }

    /** Cheap, data-dependent mixing so the compiler cannot fold a chunk to a constant. */
    private static long mix(long value, int i) {
        value ^= (value << 13) ^ i;
        value ^= (value >>> 7);
        value ^= (value << 17);
        return value;
    }
}
