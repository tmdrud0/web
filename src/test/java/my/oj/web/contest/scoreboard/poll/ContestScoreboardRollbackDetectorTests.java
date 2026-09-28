package my.oj.web.contest.scoreboard.poll;

import org.junit.jupiter.api.Test;

import static my.oj.web.submission.SubmissionResult.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code R < H} and nothing else is a rollback, and the range is persisted before the fence moves. */
class ContestScoreboardRollbackDetectorTests {

    private static PollFixture fixtureWith(long allocator, long watermark) {
        PollFixture fixture = new PollFixture(10);
        fixture.scoreboard.fenceAllocator(allocator);
        fixture.scoreboard.fenceCalls = 0;
        fixture.ledger.watermark = watermark;
        return fixture;
    }

    @Test
    void anAllocatorBelowTheWatermarkIsARollback() {
        PollFixture fixture = fixtureWith(3, 9);

        ContestScoreboardRollbackDetector.Detection detection = fixture.detector.check();

        assertThat(detection.rolledBack()).isTrue();
        assertThat(detection.range().fromExclusive()).isEqualTo(3L);
        assertThat(detection.range().throughInclusive()).isEqualTo(9L);
        assertThat(fixture.ledger.pendingRanges()).containsExactly(detection.range());
        assertThat(fixture.scoreboard.allocatorSequence()).as("fenced to H").isEqualTo(9L);
        assertThat(fixture.counter("contest.scoreboard.mysql.poll.rollbacks")).isEqualTo(1.0);
    }

    @Test
    void anAllocatorEqualToTheWatermarkIsHealthy() {
        PollFixture fixture = fixtureWith(9, 9);

        assertThat(fixture.detector.check().rolledBack()).isFalse();
        assertThat(fixture.ledger.pendingRanges()).isEmpty();
        assertThat(fixture.scoreboard.fenceCalls).isZero();
    }

    /** Redis applied results whose MySQL markers have not committed yet; that is not a rollback. */
    @Test
    void anAllocatorAboveTheWatermarkIsNotARollback() {
        PollFixture fixture = fixtureWith(12, 9);

        assertThat(fixture.detector.check().rolledBack()).isFalse();
        assertThat(fixture.ledger.pendingRanges()).isEmpty();
        assertThat(fixture.scoreboard.allocatorSequence()).isEqualTo(12L);
    }

    @Test
    void aRangeThatCouldNotBePersistedIsNotFenced() {
        PollFixture fixture = fixtureWith(3, 9);
        fixture.ledger.failOpenRange = true;

        assertThatThrownBy(fixture.detector::check).hasMessageContaining("MySQL is away");

        assertThat(fixture.scoreboard.fenceCalls).isZero();
        assertThat(fixture.scoreboard.allocatorSequence()).isEqualTo(3L);

        fixture.ledger.failOpenRange = false;
        assertThat(fixture.detector.check().rolledBack()).as("the next check sees the same rollback").isTrue();
        assertThat(fixture.scoreboard.allocatorSequence()).isEqualTo(9L);
    }

    @Test
    void aFailedFenceLeavesTheRangePendingAndTheRetryReusesIt() {
        PollFixture fixture = fixtureWith(3, 9);
        fixture.scoreboard.failFence = true;

        assertThatThrownBy(fixture.detector::check).hasMessageContaining("Redis is away");
        assertThat(fixture.ledger.pendingRanges()).hasSize(1);
        long generation = fixture.ledger.pendingRanges().get(0).generation();

        fixture.scoreboard.failFence = false;
        ContestScoreboardRollbackDetector.Detection retry = fixture.detector.check();

        assertThat(retry.range().generation()).isEqualTo(generation);
        assertThat(fixture.ledger.pendingRanges()).hasSize(1);
        assertThat(fixture.scoreboard.allocatorSequence()).isEqualTo(9L);
    }

    /** After the fence, new results are issued sequences above H and never collide with the lost range. */
    @Test
    void newResultsAfterTheFenceAreIssuedSequencesAboveTheWatermark() {
        PollFixture fixture = fixtureWith(3, 9);
        fixture.ledger.judged(20, ACCEPTED);

        fixture.poller.pollOnce();

        assertThat(fixture.ledger.sequenceOf(20)).isEqualTo(10L);
    }
}
