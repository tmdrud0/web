package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.LostRange;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.Outcome;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import my.oj.web.contest.scoreboard.stream.ContestScoreboardStreamRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a mode answers when it is asked about a range, driven through the real strategies.
 *
 * <h2>Why the real strategies and not a mock</h2>
 *
 * <p>The supervisor and the live path are tested with a mocked strategy, which is the right seam for
 * asking whether they <em>ask</em>. It is the wrong seam for asking what the answer means: the two
 * predicates that read a range are inside these classes, so a mock that returns {@code COVERED} on
 * request cannot show that a range no basis covers was taken for one that was. Every test here hands a
 * range to a real strategy and reads the answer it gives.</p>
 *
 * <h2>The four answers on one range</h2>
 *
 * <p>The same range can be recovered, retried, refused, or left to another pass, and which one it gets
 * depends on the basis the mode writes. A range whose top reaches above what this process applied was
 * never applied at all, so a basis recorded at apply time cannot find it and a replay from MySQL can -
 * which is the mode isolation these tests pin, rather than a difference in wiring. The retry answers
 * are pinned here too, and for the same reason: "another pass is running" and "the attempt failed" are
 * the same value to a caller that only reads the boolean that used to be returned, and it is the
 * difference between asking again and forgetting.</p>
 */
class ContestScoreboardRecoveryStrategyTests {

    private ContestScoreboardFullReplayService replayService;
    private ContestScoreboardStreamRecoveryService retentionGapService;

    @BeforeEach
    void setUp() {
        replayService = mock(ContestScoreboardFullReplayService.class);
        retentionGapService = mock(ContestScoreboardStreamRecoveryService.class);
    }

    /**
     * The defect this pins: a reconstruction that reached offset 4 was read as covering a range that
     * reaches offset 8, so the second rollback was reported as rebuilt without one running.
     *
     * <p>Both ranges are the ones {@code ContestScoreboardStreamLifecycle} builds: the rollback took
     * away everything between the checkpoint it restored and the highest offset this process applied at
     * the time it was observed. The interval between the two observations is when the live path applied
     * offsets 5 to 8, so the second rollback reaches further back than the first rebuild ever did.</p>
     */
    @Test
    void aRollbackThatReachesFurtherBackThanTheLastRebuildIsRebuiltAgain() {
        FullReplayRecoveryStrategy strategy = fullReplay();

        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, -1L))).isEqualTo(Outcome.COVERED);
        assertThat(strategy.rebuildHistory(range(2L, 8L, 8L, 4L)))
                .as("the second rollback reaches past what the first rebuild covered")
                .isEqualTo(Outcome.COVERED);

        verify(replayService, times(2)).replayAllContests();
    }

    /**
     * The handoff the earlier-range check exists for, and the reason it cannot be dropped: the
     * supervisor rebuilds a range and the live path asks about the delivery that anchors directly
     * above it. That is the same range, so it must not be rebuilt twice - a full replay per delivery
     * would be the cost of getting this wrong.
     */
    @Test
    void theDeliveryThatAnchorsAboveASupervisorsRebuildDoesNotRebuildItAgain() {
        FullReplayRecoveryStrategy strategy = fullReplay();

        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, -1L))).isEqualTo(Outcome.COVERED);
        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, 4L))).isEqualTo(Outcome.COVERED);

        verify(replayService, times(1)).replayAllContests();
    }

    /**
     * The same range under a basis that does hold it, which is the whole difference between the modes:
     * the judge writes MySQL before publishing, so the result offset 41 carries is stored there whether
     * or not this process ever applied it.
     */
    @Test
    void theSameRangeIsCoveredByTheBasisThatReadsMysql() {
        assertThat(fullReplay().rebuildHistory(range(40L, 41L, 40L, 40L))).isEqualTo(Outcome.COVERED);

        verify(replayService).replayAllContests();
    }

    /**
     * A rollback inside what this process applied is not the stream-offset mode's question, and it says
     * so by refusing: the offsets are retained and it is the scoreboard that moved, so the answer is to
     * rewind the consumer - which the supervisor that owns the consumer carries out. Asking the
     * MySQL-replay fallback here would answer a question about retention that was never asked.
     *
     * <p>Retryable, and that is the distinction from the sequence basis's refusal of the same range:
     * this basis has not decided anything about the range, it has said that another path owns it. The
     * rewind is the retry - the consumer is restarted at the checkpoint, the batch is delivered again,
     * and the question does not come back. Reporting it as unrecoverable would leave the range
     * remembered as answered when the thing that answers it has not run yet.</p>
     */
    @Test
    void aRollbackInsideWhatThisProcessAppliedIsLeftToTheSupervisorThatRewinds() {
        assertThat(streamOffset().rebuildHistory(range(2L, 4L, 4L, -1L)))
                .isEqualTo(Outcome.RETRYABLE_FAILURE);

        verifyNoInteractions(retentionGapService);
    }

    /**
     * A range the stream could not serve is handed over by both of its ends, as they were observed.
     *
     * <p>The second argument used to be the checkpoint's successor - an offset nothing had seen, which
     * the fallback then reported as the earliest retained offset and, one less, as the last lost one.
     * Offsets are not consecutive, so the only honest report names the range the delivery actually
     * jumped: 6 to 12 here, not 6 to 6.</p>
     */
    @Test
    void theRetentionFallbackIsToldTheRangeThatWasJumped() {
        when(retentionGapService.recoverRetentionGap(anyLong(), anyLong())).thenReturn(true);

        assertThat(streamOffset().rebuildHistory(range(5L, 12L, 5L, -1L))).isEqualTo(Outcome.COVERED);

        verify(retentionGapService).recoverRetentionGap(5L, 12L);
    }

    /**
     * The fallback configured as {@code none} is this mode's only refusal that repeating cannot change:
     * it is a decision about the configuration, so the range is remembered rather than asked about on
     * every cycle - and the strategy that made the decision is the one that says so.
     */
    @Test
    void aRangeTheFallbackIsConfiguredNotToReplayIsReportedUnrecoverable() {
        when(retentionGapService.recoverRetentionGap(anyLong(), anyLong())).thenReturn(false);

        assertThat(streamOffset().rebuildHistory(range(5L, 12L, 5L, -1L)))
                .as("retention-gap-fallback=none leaves the gap unbridged rather than moving the checkpoint")
                .isEqualTo(Outcome.UNRECOVERABLE);
    }

    /**
     * The other way a pass does not run: another pass already holds the gate.
     *
     * <p>Both Stream modes are asked inside one held pass, because the answer is the gate's and not the
     * mode's - and because the caller has to be able to tell it from a failure. Nothing is asked of any
     * basis: the gate is checked before the work and no work was done.</p>
     */
    @Test
    void aRangeAnotherPassIsAlreadyRebuildingIsNotReportedRebuilt() {
        ContestScoreboardRecoveryPassGate gate = gate();
        List<Outcome> answers = new ArrayList<>();

        gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            answers.add(new FullReplayRecoveryStrategy(replayService, gate)
                    .rebuildHistory(range(2L, 4L, 4L, -1L)));
            answers.add(new StreamOffsetRecoveryStrategy(retentionGapService, gate)
                    .rebuildHistory(range(5L, 12L, 5L, -1L)));
            return Boolean.TRUE;
        });

        assertThat(answers).containsExactly(Outcome.BUSY_RETRY_LATER, Outcome.BUSY_RETRY_LATER);
        verifyNoInteractions(replayService, retentionGapService);
    }

    /**
     * An attempt that threw is retried rather than remembered, in every mode.
     *
     * <p>An exception says nothing about whether the range is recoverable - MySQL may have been
     * unreachable for a second - and it used to be worse than that: the strategy let it out, the
     * supervisor's own catch recorded nothing, and the observed offsets had already been noted as
     * answered. Answering with an outcome instead means the exception ends the attempt and not the
     * question, which is the whole difference between a retry and a lost range.</p>
     */
    @Test
    void anAttemptThatThrewIsRetriedRatherThanRemembered() {
        when(replayService.replayAllContests()).thenThrow(new IllegalStateException("MySQL is away"));
        when(retentionGapService.recoverRetentionGap(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("MySQL is away"));

        assertThat(fullReplay().rebuildHistory(range(2L, 4L, 4L, -1L)))
                .isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(streamOffset().rebuildHistory(range(5L, 12L, 5L, -1L)))
                .isEqualTo(Outcome.RETRYABLE_FAILURE);
    }

    /**
     * A pass that threw releases the gate, so the failure is retried rather than blocking every later
     * attempt - asserted through the strategy, because that is where the two now meet.
     */
    @Test
    void aRetriedPassCanRunOnceTheFailureHasCleared() {
        when(replayService.replayAllContests())
                .thenThrow(new IllegalStateException("MySQL is away"))
                .thenReturn(1);
        FullReplayRecoveryStrategy strategy = fullReplay();

        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, -1L))).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, -1L))).isEqualTo(Outcome.COVERED);
    }

    private FullReplayRecoveryStrategy fullReplay() {
        return new FullReplayRecoveryStrategy(replayService, gate());
    }

    private StreamOffsetRecoveryStrategy streamOffset() {
        return new StreamOffsetRecoveryStrategy(retentionGapService, gate());
    }

    /** A fresh gate per strategy, so one test cannot read another's skip counter. */
    private static ContestScoreboardRecoveryPassGate gate() {
        return new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry());
    }

    /**
     * A range as the callers state it: the checkpoint Redis holds, the highest offset whose result may
     * be missing, what this process applied, and how far a completed reconstruction reached.
     */
    private static LostRange range(long checkpoint, long lastLost, long applied, long rebuiltThrough) {
        return new LostRange(checkpoint, lastLost, applied, rebuiltThrough);
    }
}
