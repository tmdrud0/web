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
            throw new IllegalStateException(
                    "Clock moved backwards for Snowflake ID generation: last="
                            + lastTimestamp + ", current=" + currentTimestamp
            );
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
        long current = currentTimeMillis();
        while (current <= timestamp) {
            current = currentTimeMillis();
        }
        return current;
    }
}
