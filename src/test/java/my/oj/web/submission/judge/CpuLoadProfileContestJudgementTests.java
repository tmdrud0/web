package my.oj.web.submission.judge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CpuLoadProfileContestJudgementTests {

    /** Small targets keep the busy loop brief while still exercising the CPU-time stop condition. */
    private static final long BASE_MILLIS = 5L;
    private static final long SLOW_MILLIS = 20L;

    @Test
    void burnsRoughlyTheConfiguredCpuTimeInsteadOfSleeping() {
        ContestJudgeLatencyProperties properties = new ContestJudgeLatencyProperties(
                true, 0.0, SLOW_MILLIS, BASE_MILLIS, 1L, "submission-id", "cpu");
        ContestJudgeLatencyClassMetrics metrics = new ContestJudgeLatencyClassMetrics("mysql");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        CpuLoadProfileContestJudgement judgement = new CpuLoadProfileContestJudgement(properties, metrics);

        // slowRatio 0.0 means every draw is "fast", so this measures baseMillis.
        judgement.judgeSubmission(submission(1L, "any-code"));

        double cpuMillis = registry.get("contest.judge.latency.class.cpu.duration")
                .tag("latency_class", "fast").timer().totalTime(TimeUnit.MILLISECONDS);
        // The busy loop only checks CPU time once per chunk, so it can overshoot the target but
        // must not undershoot it, and should not overshoot by an unreasonable multiple.
        assertThat(cpuMillis).isGreaterThanOrEqualTo(BASE_MILLIS);
        assertThat(cpuMillis).isLessThan(BASE_MILLIS * 5);

        double wallMillis = registry.get("contest.judge.latency.class.duration")
                .tag("latency_class", "fast").timer().totalTime(TimeUnit.MILLISECONDS);
        // On an otherwise-idle test runner wall time and CPU time should track closely, unlike the
        // sleep judge where wall time is spent off-CPU.
        assertThat(wallMillis).isGreaterThanOrEqualTo(cpuMillis * 0.5);
    }

    @Test
    void slowAndFastClassificationMatchesTheSleepJudgeForTheSameSeedAndDraws() {
        ContestJudgeLatencyProperties sleepProperties = new ContestJudgeLatencyProperties(
                true, 0.3, SLOW_MILLIS, BASE_MILLIS, 20260920L, "code", "sleep");
        ContestJudgeLatencyProperties cpuProperties = new ContestJudgeLatencyProperties(
                true, 0.3, SLOW_MILLIS, BASE_MILLIS, 20260920L, "code", "cpu");

        ContestJudgeLatencyClassMetrics sleepMetrics = new ContestJudgeLatencyClassMetrics("mysql");
        SimpleMeterRegistry sleepRegistry = new SimpleMeterRegistry();
        sleepMetrics.bindTo(sleepRegistry);
        LatencyProfileContestJudgement sleepJudgement =
                new LatencyProfileContestJudgement(sleepProperties, sleepMetrics);

        ContestJudgeLatencyClassMetrics cpuMetrics = new ContestJudgeLatencyClassMetrics("mysql");
        SimpleMeterRegistry cpuRegistry = new SimpleMeterRegistry();
        cpuMetrics.bindTo(cpuRegistry);
        CpuLoadProfileContestJudgement cpuJudgement =
                new CpuLoadProfileContestJudgement(cpuProperties, cpuMetrics);

        for (long submissionId = 1; submissionId <= 30; submissionId++) {
            String code = "fixture-" + submissionId;
            ContestSubmissionJudgeProjection submission = submission(submissionId, code);

            // The mode-independent decision both implementations must agree on.
            boolean expectedSlow = sleepProperties.isSlow(sleepProperties.deterministicDraw(submissionId, code));
            assertThat(cpuProperties.isSlow(cpuProperties.deterministicDraw(submissionId, code)))
                    .as("submission %d classification must not depend on mode", submissionId)
                    .isEqualTo(expectedSlow);

            sleepJudgement.judgeSubmission(submission);
            cpuJudgement.judgeSubmission(submission);
        }

        double sleepFastCount = sleepRegistry.get("contest.judge.latency.class.invocations")
                .tag("latency_class", "fast").counter().count();
        double sleepSlowCount = sleepRegistry.get("contest.judge.latency.class.invocations")
                .tag("latency_class", "slow").counter().count();
        double cpuFastCount = cpuRegistry.get("contest.judge.latency.class.invocations")
                .tag("latency_class", "fast").counter().count();
        double cpuSlowCount = cpuRegistry.get("contest.judge.latency.class.invocations")
                .tag("latency_class", "slow").counter().count();

        assertThat(cpuFastCount).isEqualTo(sleepFastCount);
        assertThat(cpuSlowCount).isEqualTo(sleepSlowCount);
        assertThat(sleepSlowCount).isGreaterThan(0).as("fixture should include at least one slow draw");
    }

    @Test
    void refusesToStartWithoutThreadCpuTimeSupport() {
        // Real hosts support it; this documents the contract without needing to fake JVM support.
        assertThat(java.lang.management.ManagementFactory.getThreadMXBean()
                .isCurrentThreadCpuTimeSupported()).isTrue();
    }

    private static ContestSubmissionJudgeProjection submission(long id, String code) {
        return new ContestSubmissionJudgeProjection() {
            @Override public Long getSubmissionId() { return id; }
            @Override public Long getContestId() { return 1L; }
            @Override public Long getProblemId() { return 1L; }
            @Override public Long getUserId() { return 1L; }
            @Override public LocalDateTime getContestStart() { return LocalDateTime.now(); }
            @Override public LocalDateTime getSubmittedTime() { return LocalDateTime.now(); }
            @Override public String getCode() { return code; }
        };
    }
}
