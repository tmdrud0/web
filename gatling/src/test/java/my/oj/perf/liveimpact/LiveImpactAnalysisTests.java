package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactAnalysis.Result;
import my.oj.perf.liveimpact.LiveImpactAnalysis.RunEvents;
import my.oj.perf.liveimpact.LiveImpactAnalysis.Second;
import my.oj.perf.liveimpact.LiveImpactAnalysis.Thresholds;
import my.oj.perf.liveimpact.LiveImpactRecords.Judged;
import my.oj.perf.liveimpact.LiveImpactRecords.LiveApply;
import my.oj.perf.liveimpact.LiveImpactRecords.Recovery;
import my.oj.perf.liveimpact.LiveImpactRecords.Request;
import my.oj.perf.liveimpact.LiveImpactRecords.TailSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed inputs with a known answer. The load is ten results a second, one every 100 ms, each judged
 * at {@code T0 + k * 100} and normally applied 100 ms later with offset {@code k}. The snapshot is at
 * 20 s, the rollback and the fault at 25 s, so {@code H} - the highest offset applied before the
 * rollback - is 249, and the 50 results between the snapshot and the fault are the lost tail.
 */
class LiveImpactAnalysisTests {

    private static final long T0 = 1_700_000_000_000L;
    private static final long SNAPSHOT = T0 + 20_000L;
    private static final long FAULT = T0 + 25_000L;
    private static final long END = T0 + 80_000L;
    private static final RunEvents RUN = new RunEvents(T0, SNAPSHOT, FAULT, FAULT, END, 249L);

    /** How a scenario applies result {@code k} judged at {@code judgedAt}: its apply instant. */
    private interface ApplyAt {
        long at(int k, long judgedAt);
    }

    private final List<LiveApply> live = new ArrayList<>();
    private final List<Judged> judged = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<TailSample> tail = new ArrayList<>();
    private final List<Recovery> recovery = new ArrayList<>();

    @Test
    void aRunTheFaultDidNotTouchIsVerdictA() {
        load((k, judgedAt) -> judgedAt + 100L);
        tailReturnsAt(FAULT + 3_000L);

        Result result = analyze();

        assertEquals("A", result.verdict(), result.metrics().get("verdictReasons"));
        assertEquals("0", result.metrics().get("newApplyStallLongestSeconds"));
        assertEquals("0", result.metrics().get("reconsumedAfterFault"));
        assertEquals(Long.toString(FAULT + 100L), result.metrics().get("T_new_resumed"));
        assertEquals("3000", result.metrics().get("tailReturnedAfterFaultMs"));
        assertEquals("100", result.metrics().get("during.reflectLatencyP95Ms"));
        assertEquals("100", result.metrics().get("before.reflectLatencyP95Ms"));
    }

    /**
     * Nothing new reaches the scoreboard for five seconds after the fault, then everything held back is
     * applied at once: a stop, a backlog of fifty, and verdict C however quickly it caught up.
     */
    @Test
    void aStopAfterTheFaultIsVerdictCWithItsLengthAndTheBacklogItLeft() {
        long resume = FAULT + 5_000L;
        load((k, judgedAt) -> judgedAt >= FAULT && judgedAt < resume ? resume + 10L : judgedAt + 100L);
        tailReturnsAt(resume);

        Result result = analyze();

        assertEquals("C", result.verdict());
        assertEquals("5", result.metrics().get("newApplyStallLongestSeconds"));
        assertEquals(Long.toString(FAULT), result.metrics().get("T_longest_stall_start"));
        assertEquals(Long.toString(resume + 10L), result.metrics().get("T_new_resumed"));
        assertEquals("50", result.metrics().get("maxBacklogAfterFault"));
        assertEquals("1", result.metrics().get("baselineBacklog"));
        assertTrue(result.metrics().get("verdictReasons").contains("stopped for 5s"));
        // The backlog is counted over one event set and comes back to its baseline once the stop ends.
        assertEquals(Long.toString(resume), result.metrics().get("T_backlog_drained"));
        assertEquals("5010", result.metrics().get("during.reflectLatencyMaxMs"));
    }

    /** Four times the latency, the same throughput, a backlog within a second of inflow: verdict B. */
    @Test
    void aSlowerReflectWithAFlatBacklogIsVerdictB() {
        load((k, judgedAt) -> judgedAt + (judgedAt >= FAULT && judgedAt < FAULT + 20_000L ? 400L : 100L));
        tailReturnsAt(FAULT + 1_000L);

        Result result = analyze();

        assertEquals("B", result.verdict(), result.metrics().get("verdictReasons"));
        assertEquals("0", result.metrics().get("newApplyStallLongestSeconds"));
        assertEquals("400", result.metrics().get("during.reflectLatencyP95Ms"));
    }

    /**
     * The lost tail read again through the live path after the fault is re-consumption, not new work:
     * it has offsets at or below H. It is reported apart, it does not move the backlog, and it does not
     * hide a stop of the results that really are new.
     */
    @Test
    void theTailReadAgainByTheLivePathIsReconsumedAndNeverCountedAsNew() {
        long resume = FAULT + 4_000L;
        load((k, judgedAt) -> judgedAt >= FAULT && judgedAt < resume ? resume + 10L : judgedAt + 100L);
        // stream-offset style: the 50 lost results come back through the consumer, one second in.
        for (int k = 200; k < 250; k++) {
            live.add(new LiveApply(FAULT + 1_000L, k, submission(k), T0 + k * 100L));
        }
        tailReturnsAt(FAULT + 1_000L);

        Result result = analyze();

        assertEquals("50", result.metrics().get("reconsumedAfterFault"));
        Second oneSecondIn = second(result, 1);
        assertEquals(50, oneSecondIn.reconsumed());
        assertEquals(0, oneSecondIn.newApplied());
        assertEquals(0, oneSecondIn.firstApplied());
        assertEquals("C", result.verdict());
        assertEquals("4", result.metrics().get("newApplyStallLongestSeconds"));
    }

    /**
     * The fault pause holds batch-1 for 1.5 s, so a healthy mode starts the recovery with fifteen results of
     * backlog and drains it. That backlog is the injector's, not growth, and the run is not a C.
     */
    @Test
    void theBacklogTheFaultPauseBuiltIsNotReadAsGrowth() {
        long pausedFrom = FAULT - 1_500L;
        load((k, judgedAt) -> judgedAt >= pausedFrom && judgedAt < FAULT ? FAULT + 50L : judgedAt + 100L);
        tailReturnsAt(FAULT + 1_000L);

        Result result = analyze();

        assertEquals("A", result.verdict(), result.metrics().get("verdictReasons"));
        assertEquals("15", result.metrics().get("backlogAtFault"));
        assertEquals("0", result.metrics().get("newApplyStallLongestSeconds"));
    }

    /** Another contest's applies are in the trace too; counted as new, they would hide this contest's stop. */
    @Test
    void liveRowsOfAnotherContestAreNotCounted() {
        long resume = FAULT + 5_000L;
        load((k, judgedAt) -> judgedAt >= FAULT && judgedAt < resume ? resume + 10L : judgedAt + 100L);
        for (long t = FAULT; t < resume; t += 50L) {
            live.add(new LiveApply(t + 5L, 10_000L + t, 99_000_000L + t, t));
        }
        tailReturnsAt(resume);

        Result result = analyze();

        assertEquals("5", result.metrics().get("newApplyStallLongestSeconds"));
        assertEquals("100", result.metrics().get("liveRowsOutsideContest"));
        assertEquals("C", result.verdict());
    }

    /** Seeded rows reach the scoreboard through the rebuild, not the stream, and are not backlog. */
    @Test
    void seededResultsAreOutsideTheBacklog() {
        for (int i = 0; i < 1_000; i++) {
            judged.add(new Judged(9_000_000L + i, T0 - 60_000L, true));
        }
        load((k, judgedAt) -> judgedAt + 100L);
        tailReturnsAt(FAULT + 1_000L);

        Result result = analyze();

        assertEquals("1", result.metrics().get("baselineBacklog"));
        assertEquals("A", result.verdict());
    }

    /**
     * The mysql-poll poller applies the seeded results itself, before the measured window. Counted as
     * first applications they would sit against no judged row and drive the backlog negative by the size
     * of the seed; they are set aside and reported on their own.
     */
    @Test
    void seededResultsAppliedByTheLivePathAreSetAside() {
        for (int i = 0; i < 1_000; i++) {
            judged.add(new Judged(9_000_000L + i, T0 - 60_000L, true));
            live.add(new LiveApply(T0 - 30_000L, 100_000L + i, 9_000_000L + i, -1L));
        }
        load((k, judgedAt) -> judgedAt + 100L);
        tailReturnsAt(FAULT + 1_000L);

        Result result = analyze();

        assertEquals("1", result.metrics().get("baselineBacklog"));
        assertEquals("1000", result.metrics().get("liveRowsSeed"));
        assertEquals("750", result.metrics().get("liveRowsTotal"));
        assertEquals("750", result.metrics().get("liveSubmissionsApplied"));
        assertEquals("A", result.verdict());
    }

    /**
     * mysql-poll: the detector's record is the detection, with the check that found it, and the range
     * recovery is the pass. The recovery re-applies the lost results under the sequences they held in the
     * range, so they are re-consumed rather than new, exactly like a Stream re-read.
     */
    @Test
    void theMySqlPollDetectionAndRangeRecoveryReadLikeAStreamModeRecovery() {
        load((k, judgedAt) -> judgedAt + 100L);
        for (int k = 200; k < 250; k++) {
            live.add(new LiveApply(FAULT + 1_500L, k, submission(k), -1L));
        }
        tailReturnsAt(FAULT + 1_500L);
        recovery.add(new Recovery("ROLLBACK_DETECTED", "scoreboard-mysql-poll-1", FAULT + 40L, FAULT + 40L,
                FAULT + 45L, -1, "(199, 249] generation 3 fenced to 249", "poll-batch"));
        recovery.add(new Recovery("PASS_START", "scoreboard-mysql-poll-3", FAULT + 1_000L, -1L, FAULT + 1_000L, -1,
                "range-recovery (199, 249] generation 3", "started"));
        recovery.add(new Recovery("CHUNK", "scoreboard-mysql-poll-3", FAULT + 1_010L, FAULT + 1_020L, FAULT + 1_500L,
                50, "range generation 3", "applied"));
        recovery.add(new Recovery("PASS_END", "scoreboard-mysql-poll-3", FAULT + 1_000L, -1L, FAULT + 1_600L, -1,
                "range-recovery (199, 249] generation 3", "COMPLETED"));

        Result result = analyze();

        assertEquals(Long.toString(FAULT + 40L), result.metrics().get("T_detected"));
        assertEquals("ROLLBACK_DETECTED (poll-batch) on scoreboard-mysql-poll-1", result.metrics().get("detectedBy"));
        assertEquals(Long.toString(FAULT + 1_000L), result.metrics().get("T_replay_start"));
        assertEquals("600", result.metrics().get("replayDurationMs"));
        assertEquals("COMPLETED", result.metrics().get("replayOutcome"));
        assertEquals("1", result.metrics().get("replayChunks"));
        assertEquals("50", result.metrics().get("replayRows"));
        assertEquals("50", result.metrics().get("reconsumedAfterFault"));
        assertEquals("1500", result.metrics().get("tailReturnedAfterFaultMs"));
        assertEquals("A", result.verdict(), result.metrics().get("verdictReasons"));
    }

    @Test
    void theRecoveryTraceGivesTheDetectionAndThePassOnItsThread() {
        load((k, judgedAt) -> judgedAt + 100L);
        tailReturnsAt(FAULT + 3_000L);
        recovery.add(new Recovery("PASS_START", "old", T0 + 1_000L, -1L, T0 + 1_000L, -1, "mysql-replay", "started"));
        recovery.add(new Recovery("GAP", "consumer-1", FAULT + 50L, -1L, FAULT + 2_050L, -1, "rollback", "covered"));
        recovery.add(new Recovery("PASS_START", "consumer-1", FAULT + 60L, -1L, FAULT + 60L, -1, "mysql-replay", "started"));
        recovery.add(new Recovery("CHUNK", "consumer-1", FAULT + 100L, FAULT + 110L, FAULT + 300L, 500, "contest 1", "applied"));
        recovery.add(new Recovery("CHUNK", "consumer-1", FAULT + 300L, FAULT + 330L, FAULT + 500L, 500, "contest 1", "applied"));
        recovery.add(new Recovery("PASS_END", "consumer-1", FAULT + 60L, -1L, FAULT + 2_000L, -1, "mysql-replay", "COVERED"));

        Result result = analyze();

        assertEquals(Long.toString(FAULT + 50L), result.metrics().get("T_detected"));
        assertEquals("GAP on consumer-1", result.metrics().get("detectedBy"));
        assertEquals(Long.toString(FAULT + 60L), result.metrics().get("T_replay_start"));
        assertEquals("1940", result.metrics().get("replayDurationMs"));
        assertEquals("consumer-1", result.metrics().get("replayThread"));
        assertEquals("2", result.metrics().get("replayChunks"));
        assertEquals("1000", result.metrics().get("replayRows"));
        assertEquals("30", result.metrics().get("chunkLockWaitMaxMs"));
        assertEquals("190", result.metrics().get("chunkHoldMaxMs"));
    }

    @Test
    void noSampleOfTheLostSetMeansTheTailFiguresAreUnavailableNotZero() {
        load((k, judgedAt) -> judgedAt + 100L);

        Result result = analyze();

        assertEquals("unavailable", result.metrics().get("lostCount"));
        assertEquals("unavailable", result.metrics().get("T_tail_returned"));
        assertEquals("unavailable", result.metrics().get("T_replay_start"));
    }

    @Test
    void theSubmitSeriesCountsOkAndKoByTheSecondTheyEnded() {
        load((k, judgedAt) -> judgedAt + 100L);
        requests.add(new Request("api-contest-submit", T0 + 900L, T0 + 1_100L, true));
        requests.add(new Request("api-contest-submit", T0 + 1_200L, T0 + 1_300L, false));

        Result result = analyze();

        Second second = result.seconds().get(1);
        assertEquals(1, second.submitOk());
        assertEquals(1, second.submitKo());
    }

    private Result analyze() {
        return LiveImpactAnalysis.analyze(RUN, Thresholds.defaults(), live, judged, requests, tail, recovery);
    }

    /** Ten results a second across the whole window; offsets are {@code k}, so H is 249 at 25 s. */
    private void load(ApplyAt applyAt) {
        for (int k = 0; k < 750; k++) {
            long judgedAt = T0 + k * 100L;
            judged.add(new Judged(submission(k), judgedAt, false));
            live.add(new LiveApply(applyAt.at(k, judgedAt), k, submission(k), judgedAt));
        }
    }

    private void tailReturnsAt(long at) {
        for (long t = FAULT; t <= FAULT + 10_000L; t += 100L) {
            tail.add(new TailSample(t, t >= at ? 50L : 0L, 50L));
        }
    }

    private static long submission(int k) {
        return 1_000_000L + k;
    }

    private static Second second(Result result, long secondsFromFault) {
        return result.seconds().stream()
                .filter(second -> second.secondsFromFault() == secondsFromFault)
                .findFirst()
                .orElseThrow();
    }
}
