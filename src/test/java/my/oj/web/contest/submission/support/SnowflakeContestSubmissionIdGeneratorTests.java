package my.oj.web.contest.submission.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}
