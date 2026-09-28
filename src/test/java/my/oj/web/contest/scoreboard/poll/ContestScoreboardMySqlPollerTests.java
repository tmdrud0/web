package my.oj.web.contest.scoreboard.poll;

import org.junit.jupiter.api.Test;

import static my.oj.web.submission.SubmissionResult.ACCEPTED;
import static my.oj.web.submission.SubmissionResult.PENDING;
import static my.oj.web.submission.SubmissionResult.WRONG_ANSWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The poller over the in-memory scoreboard and ledger: which rows it reads, where a poll stops, and what
 * it leaves recorded when Redis or MySQL fails half way.
 */
class ContestScoreboardMySqlPollerTests {

    @Test
    void appliesOnlyJudgedRowsThatHaveNoSequence() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger
                .judged(1, ACCEPTED)
                .judged(2, PENDING)
                .judged(3, WRONG_ANSWER)
                .sequenced(4, ACCEPTED, 7)
                .judged(5, ACCEPTED);
        fixture.scoreboard.fenceAllocator(7);   // Redis and MySQL agree that 7 sequences were issued

        int applied = fixture.poller.pollOnce();

        assertThat(applied).isEqualTo(3);
        assertThat(fixture.scoreboard.scored()).containsOnlyKeys(1L, 3L, 5L);
        assertThat(fixture.ledger.sequenceOf(2)).as("PENDING is never offered").isNull();
        assertThat(fixture.ledger.sequenceOf(4)).as("already sequenced, not read again").isEqualTo(7L);
        assertThat(fixture.ledger.sequenceOf(1)).isEqualTo(8L);
        assertThat(fixture.ledger.sequenceOf(3)).isEqualTo(9L);
        assertThat(fixture.ledger.sequenceOf(5)).isEqualTo(10L);
    }

    @Test
    void aRowThatAlreadyHasASequenceIsNotPolledAgain() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED);
        fixture.poller.pollOnce();
        int callsAfterFirstPoll = fixture.scoreboard.applyCalls;

        assertThat(fixture.poller.pollOnce()).isZero();
        assertThat(fixture.scoreboard.applyCalls).isEqualTo(callsAfterFirstPoll);
    }

    /** A PENDING row becomes pollable once judged, and only then. */
    @Test
    void aPendingRowIsAppliedOnceItIsJudged() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, PENDING);
        assertThat(fixture.poller.pollOnce()).isZero();

        fixture.ledger.rows.get(1L).result = ACCEPTED;

        assertThat(fixture.poller.pollOnce()).isEqualTo(1);
        assertThat(fixture.scoreboard.scored()).containsKey(1L);
    }

    /**
     * Results judged while a poll is running belong to the next poll: the upper id is fixed when the poll
     * starts, so a busy contest cannot keep one poll chasing new rows.
     */
    @Test
    void theUpperSubmissionIdIsFixedWhenThePollStarts() {
        PollFixture fixture = new PollFixture(2);
        for (long id = 1; id <= 5; id++) {
            fixture.ledger.judged(id, ACCEPTED);
        }
        fixture.ledger.afterUnsequencedQuery = ledger -> {
            long next = ledger.rows.lastKey() + 1;
            if (next <= 8) {
                ledger.judged(next, ACCEPTED);
            }
        };

        int applied = fixture.poller.pollOnce();

        assertThat(applied).isEqualTo(5);
        assertThat(fixture.ledger.throughIdsQueried).containsOnly(5L);
        assertThat(fixture.scoreboard.scored()).containsOnlyKeys(1L, 2L, 3L, 4L, 5L);

        fixture.ledger.afterUnsequencedQuery = ledger -> { };
        assertThat(fixture.poller.pollOnce()).isEqualTo(3);
        assertThat(fixture.scoreboard.scored()).containsKeys(6L, 7L, 8L);
    }

    @Test
    void markersAndTheWatermarkAreRecordedTogether() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED).judged(2, WRONG_ANSWER);

        fixture.poller.pollOnce();

        assertThat(fixture.ledger.sequenceOf(1)).isEqualTo(1L);
        assertThat(fixture.ledger.sequenceOf(2)).isEqualTo(2L);
        assertThat(fixture.ledger.watermark).isEqualTo(2L);
    }

    /**
     * Redis applied, MySQL did not record it: neither the marker nor the watermark moved, and the next poll
     * offers the same rows again - which the scoreboard absorbs, handing back the sequences it holds.
     */
    @Test
    void aLostMarkerIsRepairedByTheNextPollWithoutScoringTwice() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED).judged(2, WRONG_ANSWER);
        fixture.ledger.failNextRecord = true;

        assertThatThrownBy(fixture.poller::pollOnce).hasMessageContaining("MySQL is away");
        assertThat(fixture.ledger.sequenceOf(1)).isNull();
        assertThat(fixture.ledger.watermark).isZero();
        assertThat(fixture.scoreboard.allocatorSequence()).isEqualTo(2L);

        assertThat(fixture.poller.pollOnce()).isEqualTo(2);

        assertThat(fixture.ledger.sequenceOf(1)).isEqualTo(1L);
        assertThat(fixture.ledger.sequenceOf(2)).isEqualTo(2L);
        assertThat(fixture.ledger.watermark).isEqualTo(2L);
        assertThat(fixture.scoreboard.timesScored(1)).isEqualTo(1);
        assertThat(fixture.scoreboard.timesScored(2)).isEqualTo(1);
        assertThat(fixture.scoreboard.allocatorSequence()).as("no sequence issued for a re-poll").isEqualTo(2L);
    }

    /**
     * The race the in-script check exists for: the batch's own check passes, then Redis is restored before
     * the script runs. The script refuses instead of re-issuing a sequence MySQL already holds, what the
     * batch applied before the restore is recorded, and the detector then sees the whole lost range.
     */
    @Test
    void aRestoreAfterTheBatchCheckCannotReuseASequence() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED).judged(2, ACCEPTED);
        fixture.poller.pollOnce();
        InMemorySequencedScoreboard.Snapshot snapshot = fixture.scoreboard.snapshot();   // allocator 2
        fixture.ledger.judged(3, ACCEPTED).judged(4, ACCEPTED);
        fixture.poller.pollOnce();                                                      // H = 4
        fixture.ledger.judged(5, ACCEPTED).judged(6, ACCEPTED).judged(7, ACCEPTED);
        int[] calls = {0};
        fixture.scoreboard.beforeApply = () -> {
            if (++calls[0] == 2) {
                fixture.scoreboard.restore(snapshot);   // after row 5 took sequence 5
            }
        };

        fixture.poller.pollOnce();

        assertThat(fixture.ledger.sequenceOf(5)).isEqualTo(5L);
        assertThat(fixture.ledger.sequenceOf(6)).as("refused, left for the next poll").isNull();
        assertThat(fixture.noSequenceHeldTwice()).isTrue();
        assertThat(fixture.ledger.pendingRanges()).singleElement()
                .satisfies(range -> {
                    assertThat(range.fromExclusive()).isEqualTo(2L);
                    assertThat(range.throughInclusive()).isEqualTo(5L);
                });
        assertThat(fixture.scoreboard.allocatorSequence()).as("fenced to the watermark").isEqualTo(5L);

        fixture.scoreboard.beforeApply = () -> { };
        fixture.poller.pollOnce();
        assertThat(fixture.ledger.sequenceOf(6)).isGreaterThan(5L);
        assertThat(fixture.ledger.sequenceOf(7)).isGreaterThan(5L);
        assertThat(fixture.noSequenceHeldTwice()).isTrue();
    }

    /**
     * A restore to a snapshot taken inside the running batch leaves the allocator at or above the batch's
     * starting watermark. The expected watermark rises with every sequence the batch is issued, which is
     * what still exposes it.
     */
    @Test
    void aRestoreToASnapshotTakenInsideTheBatchIsStillRefused() {
        PollFixture fixture = new PollFixture(10);
        for (long id = 1; id <= 5; id++) {
            fixture.ledger.judged(id, ACCEPTED);
        }
        InMemorySequencedScoreboard.Snapshot[] snapshot = new InMemorySequencedScoreboard.Snapshot[1];
        int[] calls = {0};
        fixture.scoreboard.beforeApply = () -> {
            calls[0]++;
            if (calls[0] == 3) {
                snapshot[0] = fixture.scoreboard.snapshot();   // allocator 2
            }
            if (calls[0] == 5) {
                fixture.scoreboard.restore(snapshot[0]);       // allocator back to 2, batch has issued 4
            }
        };

        fixture.poller.pollOnce();

        assertThat(fixture.ledger.sequenceOf(4)).isEqualTo(4L);
        assertThat(fixture.ledger.sequenceOf(5)).isNull();
        assertThat(fixture.noSequenceHeldTwice()).isTrue();
        assertThat(fixture.ledger.pendingRanges()).singleElement()
                .satisfies(range -> assertThat(range.throughInclusive()).isEqualTo(4L));
    }
}
