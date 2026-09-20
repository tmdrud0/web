package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.LostRange;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport;
import my.oj.web.contest.scoreboard.stream.ContestScoreboardStreamRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * predicates that read a range are inside these classes, so a mock that returns {@code true} on
 * request cannot show that a range no basis covers was taken for one that was. Every test here hands a
 * range to a real strategy and reads the answer it gives.</p>
 *
 * <h2>The three answers on one range</h2>
 *
 * <p>The same range can be recoverable, unrecoverable, or not a question for the mode, and which one it
 * is depends on the basis the mode writes. A range whose top reaches above what this process applied
 * was never applied at all, so a basis recorded at apply time cannot find it and a replay from MySQL
 * can - which is the mode isolation these tests pin, rather than a difference in wiring.</p>
 */
class ContestScoreboardRecoveryStrategyTests {

    private ContestScoreboardFullReplayService replayService;
    private ContestScoreboardRedisSequenceRecoveryService sequenceService;
    private ContestScoreboardStreamRecoveryService retentionGapService;

    @BeforeEach
    void setUp() {
        replayService = mock(ContestScoreboardFullReplayService.class);
        sequenceService = mock(ContestScoreboardRedisSequenceRecoveryService.class);
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

        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, -1L))).isTrue();
        assertThat(strategy.rebuildHistory(range(2L, 8L, 8L, 4L)))
                .as("the second rollback reaches past what the first rebuild covered")
                .isTrue();

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

        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, -1L))).isTrue();
        assertThat(strategy.rebuildHistory(range(2L, 4L, 4L, 4L))).isTrue();

        verify(replayService, times(1)).replayAllContests();
    }

    /**
     * A range reaching above the applied watermark, with a checkpoint below it: the sequence basis
     * refuses rather than reporting it rebuilt.
     *
     * <p>Offset 41 is the state a failed batch leaves - the checkpoint is 40, the delivery is 42, and
     * nothing applied 41 - so its row carries no sequence and neither the sequenced-tail walk nor the
     * duplicate scan can find it. Reading the checkpoint alone would have said "inside what this
     * process applied" and let the delivery anchor past a result the standings never saw.</p>
     */
    @Test
    void aRangeReachingAboveTheAppliedWatermarkIsRefusedByTheSequenceBasis() {
        assertThat(redisSequence().rebuildHistory(range(40L, 41L, 40L, 40L)))
                .as("a basis written at apply time cannot find an offset that was never applied")
                .isFalse();

        verifyNoInteractions(sequenceService);
    }

    /**
     * The same range under a basis that does hold it, which is the whole difference between the modes:
     * the judge writes MySQL before publishing, so the result offset 41 carries is stored there whether
     * or not this process ever applied it.
     */
    @Test
    void theSameRangeIsCoveredByTheBasisThatReadsMysql() {
        assertThat(fullReplay().rebuildHistory(range(40L, 41L, 40L, 40L))).isTrue();

        verify(replayService).replayAllContests();
    }

    /**
     * A rollback inside what this process applied is not the stream-offset mode's question, and it says
     * so by refusing: the offsets are retained and it is the scoreboard that moved, so the answer is to
     * rewind the consumer - which the supervisor that owns the consumer carries out. Asking the
     * MySQL-replay fallback here would answer a question about retention that was never asked.
     */
    @Test
    void aRollbackInsideWhatThisProcessAppliedIsLeftToTheSupervisorThatRewinds() {
        assertThat(streamOffset().rebuildHistory(range(2L, 4L, 4L, -1L))).isFalse();

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

        assertThat(streamOffset().rebuildHistory(range(5L, 12L, 5L, -1L))).isTrue();

        verify(retentionGapService).recoverRetentionGap(5L, 12L);
    }

    /** The sequence basis does answer a rollback range, because every offset in it was applied here. */
    @Test
    void aRollbackInsideWhatThisProcessAppliedIsAnsweredByTheSequenceCheck() {
        when(sequenceService.check()).thenReturn(new SequenceCheckReport(1, 0L, 0, false, false));

        assertThat(redisSequence().rebuildHistory(range(2L, 4L, 4L, -1L))).isTrue();

        verify(sequenceService).check();
    }

    /**
     * A range reaching past the applied watermark is refused however deep a rebuild already ran, because
     * the two are different questions: reaching further is not the same as covering offsets that were
     * never applied here.
     */
    @Test
    void aRangeReachingPastTheAppliedWatermarkIsRefusedHoweverDeepTheRebuild() {
        when(sequenceService.check()).thenReturn(new SequenceCheckReport(1, 0L, 0, false, false));

        assertThat(redisSequence().rebuildHistory(range(2L, 4L, 4L, -1L))).isTrue();
        assertThat(redisSequence().rebuildHistory(range(2L, 6L, 4L, 4L)))
                .as("offsets 5 and 6 were never applied here, so no sequence was ever issued for them")
                .isFalse();

        verify(sequenceService, times(1)).check();
    }

    private FullReplayRecoveryStrategy fullReplay() {
        return new FullReplayRecoveryStrategy(replayService, gate());
    }

    private RedisSequenceRecoveryStrategy redisSequence() {
        return new RedisSequenceRecoveryStrategy(sequenceService, gate());
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
