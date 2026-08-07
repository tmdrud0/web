package my.oj.web.contest.submission.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "contest.submission.id", name = "strategy", havingValue = "snowflake", matchIfMissing = true)
public class SnowflakeContestSubmissionIdGenerator implements ContestSubmissionIdGenerator {

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /**
     * How far the clock may run backwards before a submission fails instead of waiting.
     *
     * <p>Measured on this stack: a load run recorded {@code last=1786062625753,
     * current=1786062625751} and failed seven users' submissions with a 500. Two milliseconds is a
     * correction, not a reset - containers on WSL2 resynchronise the guest clock against the host
     * routinely and produce exactly this - and refusing the request is a worse answer than holding
     * it for as long as the correction lasts.
     *
     * <p>100ms is fifty times the observed correction, so a real correction is nowhere near the
     * bound, and it is a tenth of the shortest delay this system already asks a client to accept
     * (the one-second Retry-After the admission limiter sends), so a submission that waits is
     * inside noise on a path whose p95 budget is measured in seconds.
     *
     * <p>Past it the generator still fails. A jump that large is not a correction to the clock the
     * previous ids were minted from, and reissuing those ids is worse than refusing the request.
     */
    private static final long MAX_BACKWARD_DRIFT_MILLIS = 100L;

    private final long epochMillis;
    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeContestSubmissionIdGenerator(
            @org.springframework.beans.factory.annotation.Value("${contest.submission.id.epoch-millis:1704067200000}") long epochMillis,
            @org.springframework.beans.factory.annotation.Value("${contest.submission.id.worker-id:0}") long workerId
    ) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("contest.submission.id.worker-id must be between 0 and " + MAX_WORKER_ID);
        }
        this.epochMillis = epochMillis;
        this.workerId = workerId;
    }

    @Override
    public synchronized long nextId() {
        long currentTimestamp = currentTimeMillis();

        if (currentTimestamp < lastTimestamp) {
            long driftMillis = lastTimestamp - currentTimestamp;
            if (driftMillis > MAX_BACKWARD_DRIFT_MILLIS) {
                throw new IllegalStateException(
                        "Clock moved backwards for Snowflake ID generation: last="
                                + lastTimestamp + ", current=" + currentTimestamp
                                + " (" + driftMillis + "ms, tolerance " + MAX_BACKWARD_DRIFT_MILLIS + "ms)"
                );
            }
            // Wait for the clock to reach the millisecond ids were last minted from, which the
            // sequence below then shares as it does for any other repeated millisecond. The wait
            // is bounded by the tolerance because anything longer threw above.
            currentTimestamp = waitUntil(lastTimestamp);
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                currentTimestamp = waitUntilNextMillis(currentTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - epochMillis) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private long waitUntilNextMillis(long timestamp) {
        return waitUntil(timestamp + 1);
    }

    private long waitUntil(long targetMillis) {
        long current = currentTimeMillis();
        while (current < targetMillis) {
            Thread.onSpinWait();
            current = currentTimeMillis();
        }
        return current;
    }
}
