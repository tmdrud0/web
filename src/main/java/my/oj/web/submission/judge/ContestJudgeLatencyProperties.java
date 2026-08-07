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
 */
@ConfigurationProperties(prefix = "contest.submission.judge.latency")
public record ContestJudgeLatencyProperties(boolean enabled,
                                            Double slowRatio,
                                            Long slowMillis,
                                            Long baseMillis) {

    private static final double DEFAULT_SLOW_RATIO = 0.01d;
    private static final long DEFAULT_SLOW_MILLIS = 2000L;
    private static final long DEFAULT_BASE_MILLIS = 10L;

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
     * Compares against a caller-supplied draw so the decision is testable without stubbing a
     * random source. A ratio of zero must never draw slow, which {@code <} gives and {@code <=}
     * would not.
     */
    public boolean isSlow(double draw) {
        return draw < effectiveSlowRatio();
    }
}
