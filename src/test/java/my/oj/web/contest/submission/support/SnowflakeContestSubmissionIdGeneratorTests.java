package my.oj.web.contest.submission.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeContestSubmissionIdGeneratorTests {

    @Test
    void generatesUniqueIds() {
        SnowflakeContestSubmissionIdGenerator generator =
                new SnowflakeContestSubmissionIdGenerator(1704067200000L, 7L);

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }

        assertThat(ids).hasSize(10_000);
    }

    @Test
    void generatesMonotonicIdsOnSingleNode() {
        SnowflakeContestSubmissionIdGenerator generator =
                new SnowflakeContestSubmissionIdGenerator(1704067200000L, 3L);

        long previous = generator.nextId();
        for (int i = 0; i < 1_000; i++) {
            long current = generator.nextId();
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }

    /**
     * A small correction backwards has to be waited out rather than refused. This is the failure a
     * load run recorded seven times - {@code last=1786062625753, current=1786062625751} - where a
     * two-millisecond resync of a container clock answered a user's submission with a 500.
     */
    @Test
    void aSmallBackwardCorrectionWaitsInsteadOfFailing() {
        ScriptedClock generator = new ScriptedClock(1_000_000L, 1_000_000L - 2L, 1_000_000L + 1L);

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(second).isGreaterThan(first);
        assertThat(generator.remainingReadings()).isZero();
    }

    /**
     * Past the tolerance it still fails. A jump that large is not a correction to the clock the
     * earlier ids were minted from, and reissuing those ids is worse than refusing the request.
     */
    @Test
    void aLargeJumpBackwardsStillFails() {
        ScriptedClock generator = new ScriptedClock(1_000_000L, 1_000_000L - 5_000L);

        generator.nextId();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Clock moved backwards");
    }

    /**
     * Waiting must not reuse a (timestamp, sequence) pair: once the clock is back where it was,
     * the caught-up millisecond is shared through the sequence like any other repeat.
     */
    @Test
    void idsStayUniqueAcrossACorrection() {
        ScriptedClock generator = new ScriptedClock(
                1_000_000L, 1_000_000L, 1_000_000L - 1L, 1_000_000L, 1_000_000L
        );

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            ids.add(generator.nextId());
        }

        assertThat(ids).hasSize(4);
    }

    /**
     * Drives the generator's clock from a script so a correction is reproducible. The last
     * reading is held once the script runs out, which is what lets a wait loop terminate.
     */
    private static final class ScriptedClock extends SnowflakeContestSubmissionIdGenerator {

        private final Deque<Long> readings;
        private long last;

        private ScriptedClock(long... millis) {
            super(0L, 1L);
            this.readings = new ArrayDeque<>();
            for (long value : millis) {
                this.readings.add(value);
            }
            this.last = millis[millis.length - 1];
        }

        int remainingReadings() {
            return readings.size();
        }

        @Override
        protected long currentTimeMillis() {
            if (!readings.isEmpty()) {
                last = readings.poll();
            }
            return last;
        }
    }
}
