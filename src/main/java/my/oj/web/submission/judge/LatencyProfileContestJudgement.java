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
 * Stands in for a judge that actually takes time.
 *
 * <p>{@link ContestProvisionalJudgement} returns immediately, so every latency figure measured so
 * far carries a judge cost of zero and describes queueing, the database, RabbitMQ and Redis only.
 * The load model in {@code docs/CONTEST_SUBMISSION_PIPELINE_HISTORY.md} 9.3 asks for the opposite:
 * a mean around ten milliseconds with a p99/p999 near two seconds. That shape matters because the
 * tail, not the mean, is what occupies a listener thread - one submission in a hundred holding a
 * consumer for two seconds costs two hundred times what the mean suggests.
 *
 * <p>Blocking is the point rather than an implementation shortcut. A real judge holds the consumer
 * while it runs, and {@code prefetch=1} with {@code concurrency=64} means the pool is what
 * absorbs it, so sleeping here reproduces the contention a non-blocking stub cannot.
 *
 * <p>Off unless {@code contest.submission.judge.latency.enabled} is true. When it is, exactly one
 * of this class and {@link CpuLoadProfileContestJudgement} is active, selected by
 * {@code contest.submission.judge.latency.mode} ({@code sleep}, the default, here; {@code cpu}
 * there); {@link ContestProvisionalJudgement} backs off whenever latency simulation is enabled at
 * all. So exactly one {@link ContestSubmissionJudgement} implementation exists in any context.
 */
@Component
@ConditionalOnExpression("${contest.submission.judge.latency.enabled:false} "
        + "and '${contest.submission.judge.latency.mode:sleep}'.equalsIgnoreCase('sleep')")
@EnableConfigurationProperties(ContestJudgeLatencyProperties.class)
public class LatencyProfileContestJudgement implements ContestSubmissionJudgement {

    private final ContestJudgeLatencyProperties properties;
    private final ContestJudgeLatencyClassMetrics latencyClassMetrics;
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    public LatencyProfileContestJudgement(ContestJudgeLatencyProperties properties,
                                          ContestJudgeLatencyClassMetrics latencyClassMetrics) {
        this.properties = properties;
        this.latencyClassMetrics = latencyClassMetrics;
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
        long started = System.nanoTime();
        long cpuStarted = threadMXBean.isCurrentThreadCpuTimeSupported()
                ? threadMXBean.getCurrentThreadCpuTime() : -1L;
        try {
            sleep(slow ? properties.effectiveSlowMillis() : properties.effectiveBaseMillis());
        } finally {
            latencyClassMetrics.record(latencyClass, System.nanoTime() - started);
            if (cpuStarted >= 0L) {
                latencyClassMetrics.recordCpu(latencyClass,
                        threadMXBean.getCurrentThreadCpuTime() - cpuStarted);
            }
        }
        return SubmissionResult.PARTIAL_ACCEPTED;
    }

    /**
     * A judge that is interrupted has not judged anything, so the interrupt is restored and the
     * listener is allowed to fail rather than persisting a result the judge never produced.
     */
    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Judge latency simulation was interrupted", e);
        }
    }
}
