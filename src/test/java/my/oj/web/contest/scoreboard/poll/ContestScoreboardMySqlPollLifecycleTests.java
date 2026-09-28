package my.oj.web.contest.scoreboard.poll;

import org.junit.jupiter.api.Test;

import static my.oj.web.submission.SubmissionResult.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;

/** The three triggers: startup before the first poll, the periodic check, and ownership. */
class ContestScoreboardMySqlPollLifecycleTests {

    /** A JVM that starts on a restored Redis fences the allocator before it issues any sequence. */
    @Test
    void theStartupCheckRunsBeforeTheFirstPoll() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.sequenced(1, ACCEPTED, 5).judged(2, ACCEPTED);   // H = 5, Redis came back empty
        ContestScoreboardMySqlPollLifecycle lifecycle = fixture.lifecycle(ContestScoreboardPollOwnership.ALWAYS);

        lifecycle.pollTick();

        assertThat(lifecycle.startupChecked()).isTrue();
        assertThat(fixture.ledger.pendingRanges()).singleElement()
                .satisfies(range -> assertThat(range.throughInclusive()).isEqualTo(5L));
        assertThat(fixture.ledger.sequenceOf(2)).isEqualTo(6L);
    }

    /** No result is judged, and the restore is still found. */
    @Test
    void thePeriodicCheckFindsARollbackWithoutAnyNewResult() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED).judged(2, ACCEPTED);
        ContestScoreboardMySqlPollLifecycle lifecycle = fixture.lifecycle(ContestScoreboardPollOwnership.ALWAYS);
        InMemorySequencedScoreboard.Snapshot empty = fixture.scoreboard.snapshot();
        lifecycle.pollTick();
        fixture.scoreboard.restore(empty);

        lifecycle.checkTick();

        assertThat(fixture.ledger.pendingRanges()).hasSize(1);
        lifecycle.recoveryTick();
        assertThat(fixture.ledger.pendingRanges()).isEmpty();
        assertThat(fixture.scoreboard.scored()).containsOnlyKeys(1L, 2L);
    }

    @Test
    void anInstanceThatDoesNotHoldOwnershipDoesNothing() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED);
        ContestScoreboardPollOwnership notOwner = new ContestScoreboardPollOwnership() {
            @Override
            public boolean holds() {
                return false;
            }

            @Override
            public void release() {
            }
        };
        ContestScoreboardMySqlPollLifecycle lifecycle = fixture.lifecycle(notOwner);

        lifecycle.pollTick();
        lifecycle.checkTick();
        lifecycle.recoveryTick();

        assertThat(fixture.scoreboard.applyCalls).isZero();
        assertThat(lifecycle.startupChecked()).isFalse();
    }

    /** A tick that fails is counted and the next tick retries; the scheduled task is never cancelled. */
    @Test
    void aFailingTickIsCountedAndRetried() {
        PollFixture fixture = new PollFixture(10);
        fixture.ledger.judged(1, ACCEPTED);
        fixture.ledger.failNextRecord = true;
        ContestScoreboardMySqlPollLifecycle lifecycle = fixture.lifecycle(ContestScoreboardPollOwnership.ALWAYS);

        lifecycle.pollTick();
        assertThat(fixture.registry.find("contest.scoreboard.mysql.poll.failures").tag("task", "poll").counter())
                .isNotNull();

        lifecycle.pollTick();
        assertThat(fixture.ledger.sequenceOf(1)).isEqualTo(1L);
    }
}
