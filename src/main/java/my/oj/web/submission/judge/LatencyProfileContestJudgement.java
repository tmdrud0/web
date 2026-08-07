package my.oj.web.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgement;
import my.oj.web.submission.SubmissionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

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
 * <p>Off unless {@code contest.submission.judge.latency.enabled} is true, and
 * {@link ContestProvisionalJudgement} backs off when it is, so exactly one implementation exists
 * in any context.
 */
@Component
@ConditionalOnProperty(prefix = "contest.submission.judge.latency", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ContestJudgeLatencyProperties.class)
public class LatencyProfileContestJudgement implements ContestSubmissionJudgement {

    private final ContestJudgeLatencyProperties properties;

    public LatencyProfileContestJudgement(ContestJudgeLatencyProperties properties) {
        this.properties = properties;
    }

    @Override
    public SubmissionResult judgeSubmission(ContestSubmissionJudgeProjection submission) {
        sleep(properties.isSlow(ThreadLocalRandom.current().nextDouble())
                ? properties.effectiveSlowMillis()
                : properties.effectiveBaseMillis());
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
