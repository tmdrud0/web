package my.oj.web.contest.scoreboard.poll;

import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static my.oj.web.submission.SubmissionResult.ACCEPTED;
import static my.oj.web.submission.SubmissionResult.PENDING;
import static my.oj.web.submission.SubmissionResult.WRONG_ANSWER;
import static org.assertj.core.api.Assertions.assertThat;

/** Recovery of persisted {@code (R, H]} ranges: exactly the range, chunk by chunk, and idempotently. */
class ContestScoreboardRangeRecoveryTests {

    /**
     * Rows 1..8 were applied under sequences 1..8, then Redis was restored to allocator 3 and the
     * detector fenced it to 8. Only sequences 4..8 are replayed; 1..3 are still in Redis.
     */
    private static PollFixture rolledBackAfterEight(int chunkSize, int maxIterations) {
        PollFixture fixture = new PollFixture(new InMemorySequencedScoreboard(), new InMemorySequenceLedger(),
                100, chunkSize, maxIterations);
        LongStream.rangeClosed(1, 8).forEach(id -> fixture.ledger.judged(id, id % 3 == 0 ? WRONG_ANSWER : ACCEPTED));
        fixture.ledger.judged(3, WRONG_ANSWER);
        InMemorySequencedScoreboard.Snapshot[] snapshot = new InMemorySequencedScoreboard.Snapshot[1];
        int[] calls = {0};
        fixture.scoreboard.beforeApply = () -> {
            if (++calls[0] == 4) {
                snapshot[0] = fixture.scoreboard.snapshot();
            }
        };
        fixture.poller.pollOnce();
        fixture.scoreboard.beforeApply = () -> { };
        fixture.scoreboard.restore(snapshot[0]);
        fixture.detector.check();
        return fixture;
    }

    @Test
    void replaysExactlyTheLostRangeAndCompletesIt() {
        PollFixture fixture = rolledBackAfterEight(2, 100);
        ContestScoreboardRecoveryRange range = fixture.ledger.pendingRanges().get(0);
        assertThat(range.fromExclusive()).isEqualTo(3L);
        assertThat(range.throughInclusive()).isEqualTo(8L);
        int callsBefore = fixture.scoreboard.applyCalls;

        int recovered = fixture.recovery.recoverPending();

        assertThat(recovered).isEqualTo(5);
        assertThat(fixture.scoreboard.applyCalls - callsBefore).as("rows 1..3 are never touched").isEqualTo(5);
        assertThat(fixture.scoreboard.scored()).containsOnlyKeys(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(fixture.ledger.sequenceOf(1)).isEqualTo(1L);
        LongStream.rangeClosed(4, 8).forEach(id -> assertThat(fixture.ledger.sequenceOf(id)).isGreaterThan(8L));
        assertThat(fixture.ledger.completed(range.generation())).isTrue();
        assertThat(fixture.noSequenceHeldTwice()).isTrue();
        assertThat(fixture.ledger.watermark).isEqualTo(13L);
    }

    /**
     * Rows leave the range as their markers commit. Reading the first page again each time is what keeps
     * a keyset from stepping over rows whose key changed under it - chunk size 2 over 5 rows would skip
     * two of them with an ordinary "after the last sequence" keyset.
     */
    @Test
    void rowsMovingOutOfTheRangeDoNotHideTheOthers() {
        PollFixture fixture = rolledBackAfterEight(2, 100);

        fixture.recovery.recoverPending();

        assertThat(fixture.ledger.judgedResultsInRange(3, 8, 100)).isEmpty();
    }

    @Test
    void aPendingRowInsideTheRangeIsNotApplied() {
        PollFixture fixture = rolledBackAfterEight(2, 100);
        fixture.ledger.rows.get(6L).result = PENDING;   // rejudge reset it while the range was pending

        fixture.recovery.recoverPending();

        assertThat(fixture.scoreboard.scored()).doesNotContainKey(6L);
        assertThat(fixture.ledger.sequenceOf(6)).isEqualTo(6L);
        assertThat(fixture.ledger.pendingRanges()).isEmpty();
    }

    @Test
    void aRangeThatSpendsItsIterationsStaysPending() {
        PollFixture fixture = rolledBackAfterEight(1, 2);

        fixture.recovery.recoverPending();

        assertThat(fixture.ledger.pendingRanges()).hasSize(1);
        assertThat(fixture.counter("contest.scoreboard.mysql.poll.recovery.unresolved")).isEqualTo(1.0);

        fixture.recovery.recoverPending();
        fixture.recovery.recoverPending();
        assertThat(fixture.ledger.pendingRanges()).isEmpty();
        assertThat(fixture.noSequenceHeldTwice()).isTrue();
    }

    /** A restarted JVM - new components over the same MySQL and Redis - resumes the pending range. */
    @Test
    void aRangePersistedBeforeARestartIsResumed() {
        PollFixture before = rolledBackAfterEight(2, 1);
        before.recovery.recoverPending();
        assertThat(before.ledger.pendingRanges()).hasSize(1);

        PollFixture after = new PollFixture(before.scoreboard, before.ledger, 100, 2, 100);
        ContestScoreboardMySqlPollLifecycle restarted = after.lifecycle(ContestScoreboardPollOwnership.ALWAYS);
        restarted.pollTick();
        restarted.recoveryTick();

        assertThat(after.ledger.pendingRanges()).isEmpty();
        assertThat(after.scoreboard.scored()).containsOnlyKeys(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(after.noSequenceHeldTwice()).isTrue();
    }

    /**
     * A second rollback while the first range is being recovered writes a new generation. Completing the
     * first compares against its own generation, so the second stays pending until it is itself empty.
     */
    @Test
    void completingAnOlderGenerationLeavesANewerOnePending() {
        PollFixture fixture = rolledBackAfterEight(2, 100);
        ContestScoreboardRecoveryRange first = fixture.ledger.pendingRanges().get(0);
        fixture.ledger.judged(9, ACCEPTED).judged(10, ACCEPTED);
        fixture.poller.pollOnce();                                   // 9, 10 -> 9, 10
        InMemorySequencedScoreboard.Snapshot snapshot = fixture.scoreboard.snapshot();
        fixture.ledger.judged(11, ACCEPTED);
        fixture.poller.pollOnce();                                   // 11 -> 11
        fixture.scoreboard.restore(snapshot);                        // second rollback: R=10, H=11
        fixture.detector.check();
        ContestScoreboardRecoveryRange second = fixture.ledger.pendingRanges().get(1);
        assertThat(second.generation()).isGreaterThan(first.generation());

        assertThat(fixture.recovery.recover(first).outcome()).isEqualTo(ContestScoreboardRangeRecovery.Outcome.COMPLETED);

        assertThat(fixture.ledger.completed(first.generation())).isTrue();
        assertThat(fixture.ledger.completed(second.generation())).isFalse();
        assertThat(fixture.ledger.completeRange(first.generation())).as("a repeated completion changes nothing").isFalse();

        fixture.recovery.recoverPending();
        assertThat(fixture.ledger.pendingRanges()).isEmpty();
        assertThat(fixture.scoreboard.scored()).containsKey(11L);
        assertThat(fixture.noSequenceHeldTwice()).isTrue();
    }

    /** Replaying a completed range again changes nothing: the rows are no longer in it. */
    @Test
    void recoveryIsIdempotent() {
        PollFixture fixture = rolledBackAfterEight(2, 100);
        ContestScoreboardRecoveryRange range = fixture.ledger.pendingRanges().get(0);
        fixture.recovery.recoverPending();
        int calls = fixture.scoreboard.applyCalls;

        assertThat(fixture.recovery.recover(range).applied()).isZero();
        assertThat(fixture.scoreboard.applyCalls).isEqualTo(calls);
    }
}
