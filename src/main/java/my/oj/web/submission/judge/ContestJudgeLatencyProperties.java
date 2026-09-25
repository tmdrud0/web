package my.oj.web.submission.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shape of the simulated judge, as a fraction of submissions that are slow rather than a
 * distribution: the tail is what occupies listener threads, and a ratio states directly how much
 * of the consumer pool the tail is entitled to.
 *
 * @param enabled    whether {@link LatencyProfileContestJudgement} replaces the immediate stub
 * @param slowRatio  fraction of submissions given {@code slowMillis}; 0.01 is the p99 that
 *                   pipeline history 9.3 asks for
 * @param slowMillis how long a slow judgement blocks its consumer
 * @param baseMillis how long every other judgement takes
 * @param mode       {@code sleep} (default) blocks the consumer without using CPU;
 *                   {@code cpu} spends the same duration in on-thread computation instead, so the
 *                   dispatcher and the judge compete for the same core. Same seed, same slow/fast
 *                   classification either way &mdash; only how the time is spent changes.
 */
@ConfigurationProperties(prefix = "contest.submission.judge.latency")
public record ContestJudgeLatencyProperties(boolean enabled,
                                            Double slowRatio,
                                            Long slowMillis,
                                            Long baseMillis,
                                            Long seed,
                                            String keySource,
                                            String mode) {

    private static final double DEFAULT_SLOW_RATIO = 0.01d;
    private static final long DEFAULT_SLOW_MILLIS = 2000L;
    private static final long DEFAULT_BASE_MILLIS = 10L;
    public static final String MODE_SLEEP = "sleep";
    public static final String MODE_CPU = "cpu";

    public double effectiveSlowRatio() {
        return slowRatio == null ? DEFAULT_SLOW_RATIO : slowRatio;
    }

    public long effectiveSlowMillis() {
        return slowMillis == null ? DEFAULT_SLOW_MILLIS : slowMillis;
    }

    public long effectiveBaseMillis() {
        return baseMillis == null ? DEFAULT_BASE_MILLIS : baseMillis;
    }

    /**
     * Defaults to {@link #MODE_SLEEP} so existing deployments and the base experiment keep
     * blocking without CPU cost unless {@code contest.submission.judge.latency.mode=cpu} is set
     * explicitly (e.g. via the {@code CONTEST_JUDGE_LATENCY_MODE} environment variable).
     */
    public String effectiveMode() {
        return mode == null ? MODE_SLEEP : mode;
    }

    public boolean isCpuMode() {
        return MODE_CPU.equalsIgnoreCase(effectiveMode());
    }

    /**
     * Compares against a caller-supplied draw so the decision is testable without stubbing a
     * random source. A ratio of zero must never draw slow, which {@code <} gives and {@code <=}
     * would not.
     */
    public boolean isSlow(double draw) {
        return draw < effectiveSlowRatio();
    }

    /**
     * Returns a repeatable draw when a load-test seed is configured. The default key is the
     * submission id, so retries select the same latency. The comparison harness opts into a
     * stable code key because Snowflake ids differ between otherwise identical isolated runs.
     * With no seed the caller keeps the previous ThreadLocalRandom behavior.
     */
    public double deterministicDraw(long submissionId) {
        return deterministicDraw(submissionId, null);
    }

    public double deterministicDraw(long submissionId, String code) {
        if (seed == null) {
            throw new IllegalStateException("No deterministic judge latency seed is configured");
        }
        long key = "code".equalsIgnoreCase(keySource) && code != null
                ? stableHash(code)
                : submissionId;
        long value = seed ^ key;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
