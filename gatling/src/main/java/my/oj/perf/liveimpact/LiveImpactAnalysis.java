package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactRecords.Judged;
import my.oj.perf.liveimpact.LiveImpactRecords.LiveApply;
import my.oj.perf.liveimpact.LiveImpactRecords.Recovery;
import my.oj.perf.liveimpact.LiveImpactRecords.Request;
import my.oj.perf.liveimpact.LiveImpactRecords.TailSample;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one run's records into the three per-second series and the figures the verdict reads.
 *
 * <h2>"New", decided the same way for every mode</h2>
 *
 * <p>An event is <em>new</em> when its stream offset is above {@code H}, the highest offset the
 * scoreboard had applied just before the rollback. The code path that applied it plays no part:
 * {@code stream-offset} re-reads the lost tail through the live consumer, and {@code full-replay} can
 * re-read the stream after a resubscribe, so counting "whatever the live path applied" would count the
 * tail as new work in one mode and not in another. A live row at or below {@code H} applied after the
 * fault is <em>re-consumed</em>, and is reported in its own column.</p>
 *
 * <p>Under the {@code mysql-poll} delivery there is no stream offset. The position of a result in the
 * delivery order is its Redis sequence, so a live row's offset is that sequence and {@code H} is the Redis
 * allocator just before the rollback ({@code preRollbackSeq}). The poller issues new results sequences
 * above the fenced watermark, and a range recovery reports each lost result under the sequence it held
 * inside the range: the same "new" and "re-consumed" as the Stream modes.</p>
 *
 * <h2>Backlog, over one event set</h2>
 *
 * <p>{@code backlog(t)} is the load's judged results up to {@code t} minus the same results whose
 * first application happened by {@code t}. Both sides range over the non-seed submissions of the
 * experiment contest, so the difference is a count of real results waiting for the scoreboard and
 * returns to its baseline once the pipeline has caught up. A submission's first application is the
 * earliest live row that carried it; for everything above {@code H} that is its only application.</p>
 *
 * <h2>Seeded rows</h2>
 *
 * <p>A seeded result is on the scoreboard before the load, and it is not part of the event set. Under the
 * Stream it never appears in the trace (the pre-load rebuild puts it there); under the {@code mysql-poll}
 * delivery the poller itself applies it, before the measured window, so its live rows are set aside here
 * and counted on their own.</p>
 *
 * <h2>What this does not know</h2>
 *
 * <p>Results a replay put back are not live rows, so the tail's return is read from the poller, not
 * from here. A trace that dropped rows (the batch role's status file says so) makes every live-side
 * figure a lower bound; the caller records that as a completeness flag.</p>
 */
final class LiveImpactAnalysis {

    static final String UNAVAILABLE = "unavailable";

    /** The {@code mysql-poll} detector's record: the Redis allocator found below the MySQL watermark. */
    static final String ROLLBACK_DETECTED = "ROLLBACK_DETECTED";

    /** The instants and offsets the runner recorded, all in the container frame. */
    record RunEvents(long measureFromMs,
                     long snapshotAtMs,
                     long rollbackAtMs,
                     long faultAtMs,
                     long measureToMs,
                     long preRollbackOffset) {

        RunEvents {
            if (!(measureFromMs <= snapshotAtMs && snapshotAtMs <= rollbackAtMs && rollbackAtMs <= faultAtMs
                    && faultAtMs < measureToMs)) {
                throw new IllegalArgumentException("Run instants are out of order: measureFrom=" + measureFromMs
                        + " snapshot=" + snapshotAtMs + " rollback=" + rollbackAtMs + " fault=" + faultAtMs
                        + " measureTo=" + measureToMs);
            }
        }
    }

    /**
     * The verdict's thresholds. Each is recorded next to the verdict so a reader can see what "flat" and
     * "stopped" were taken to mean in that run.
     *
     * @param stallSeconds            a run of this many consecutive seconds with judged results and no new
     *                                application is a stop
     * @param throughputTolerance     new applications below {@code (1 - this)} of judged results over the
     *                                recovery window is a throughput loss
     * @param backlogToleranceSeconds backlog growth above the baseline by more than this many seconds of the
     *                                baseline judged rate is a growing backlog
     * @param latencyTolerance        a p95 reflect latency above {@code (1 + this)} of the baseline's is a
     *                                latency increase
     * @param minDuringSeconds        the recovery window is at least this long, so a mode that recovered
     *                                instantly is still observed for a moment
     */
    record Thresholds(int stallSeconds,
                      double throughputTolerance,
                      double backlogToleranceSeconds,
                      double latencyTolerance,
                      int minDuringSeconds) {

        static Thresholds defaults() {
            return new Thresholds(2, 0.10, 1.0, 0.20, 10);
        }
    }

    /** One second of the three series and what the verdict derives from them. */
    record Second(long epochSecond,
                  long secondsFromFault,
                  String phase,
                  long submitOk,
                  long submitKo,
                  long judged,
                  long firstApplied,
                  long newApplied,
                  long reconsumed,
                  long backlog,
                  String tailPresent) {
    }

    record Result(Map<String, String> metrics, List<Second> seconds) {

        String verdict() {
            return metrics.get("verdict");
        }
    }

    private LiveImpactAnalysis() {
    }

    static Result analyze(RunEvents run,
                          Thresholds thresholds,
                          Collection<LiveApply> liveRows,
                          Collection<Judged> judgedRows,
                          Collection<Request> submitRequests,
                          Collection<TailSample> tailSamples,
                          Collection<Recovery> recoveryRows) {
        Map<String, String> metrics = new LinkedHashMap<>();
        long h = run.preRollbackOffset();

        // --- the live side: first application per submission, and the re-consumed rows -------------
        // Only the experiment contest's submissions: the trace records every contest the consumer
        // applies, and another contest's rows counted as new applies could hide this contest's stop.
        java.util.Set<Long> contestSubmissions = new java.util.HashSet<>();
        java.util.Set<Long> seedSubmissions = new java.util.HashSet<>();
        for (Judged row : judgedRows) {
            contestSubmissions.add(row.submissionId());
            if (row.seed()) {
                seedSubmissions.add(row.submissionId());
            }
        }
        Map<Long, LiveApply> first = new HashMap<>();
        List<LiveApply> reconsumed = new ArrayList<>();
        long outsideContest = 0L;
        long seedRows = 0L;
        for (LiveApply row : liveRows) {
            if (!contestSubmissions.isEmpty() && !contestSubmissions.contains(row.submissionId())) {
                outsideContest++;
                continue;
            }
            if (seedSubmissions.contains(row.submissionId())) {
                seedRows++;
                continue;
            }
            LiveApply known = first.get(row.submissionId());
            if (known == null || row.appliedAtMs() < known.appliedAtMs()) {
                if (known != null) {
                    reconsumedIfAfterFault(known, run, h, reconsumed);
                }
                first.put(row.submissionId(), row);
            } else {
                reconsumedIfAfterFault(row, run, h, reconsumed);
            }
        }

        // --- the judged side: the load's results only ------------------------------------------------
        List<Judged> live = judgedRows.stream().filter(row -> !row.seed()).toList();

        // --- per-second bins -------------------------------------------------------------------------
        long fromSecond = Math.floorDiv(run.measureFromMs(), 1000L);
        long toSecond = Math.floorDiv(run.measureToMs(), 1000L);
        int size = (int) (toSecond - fromSecond + 1);
        long[] submitOk = new long[size];
        long[] submitKo = new long[size];
        long[] judged = new long[size];
        long[] firstApplied = new long[size];
        long[] newApplied = new long[size];
        long[] reconsumedPerSecond = new long[size];
        long judgedBefore = 0L;
        long appliedBefore = 0L;

        for (Request request : submitRequests) {
            int index = bin(request.endMs(), fromSecond, size);
            if (index < 0) {
                continue;
            }
            if (request.ok()) {
                submitOk[index]++;
            } else {
                submitKo[index]++;
            }
        }
        for (Judged row : live) {
            int index = bin(row.judgedAtMs(), fromSecond, size);
            if (index >= 0) {
                judged[index]++;
            } else if (row.judgedAtMs() < run.measureFromMs()) {
                judgedBefore++;
            }
        }
        for (LiveApply row : first.values()) {
            int index = bin(row.appliedAtMs(), fromSecond, size);
            if (index >= 0) {
                firstApplied[index]++;
                if (row.offset() > h) {
                    newApplied[index]++;
                }
            } else if (row.appliedAtMs() < run.measureFromMs()) {
                appliedBefore++;
            }
        }
        for (LiveApply row : reconsumed) {
            int index = bin(row.appliedAtMs(), fromSecond, size);
            if (index >= 0) {
                reconsumedPerSecond[index]++;
            }
        }
        long[] backlog = new long[size];
        long cumulativeJudged = judgedBefore;
        long cumulativeApplied = appliedBefore;
        for (int i = 0; i < size; i++) {
            cumulativeJudged += judged[i];
            cumulativeApplied += firstApplied[i];
            backlog[i] = cumulativeJudged - cumulativeApplied;
        }

        // --- the instants -----------------------------------------------------------------------------
        int faultIndex = (int) (Math.floorDiv(run.faultAtMs(), 1000L) - fromSecond);
        int snapshotIndex = (int) (Math.floorDiv(run.snapshotAtMs(), 1000L) - fromSecond);

        Long newResumedAt = first.values().stream()
                .filter(row -> row.offset() > h && row.appliedAtMs() >= run.faultAtMs())
                .map(LiveApply::appliedAtMs)
                .min(Long::compare)
                .orElse(null);

        long lostTotal = tailSamples.stream().mapToLong(TailSample::total).max().orElse(-1L);
        Long tailReturnedAt = lostTotal > 0L
                ? tailSamples.stream()
                        .filter(sample -> sample.atMs() >= run.rollbackAtMs() && sample.present() >= sample.total())
                        .map(TailSample::atMs)
                        .min(Long::compare)
                        .orElse(null)
                : null;

        // Baseline backlog: the most the pipeline held back in normal operation.
        long baselineBacklog = 0L;
        for (int i = 0; i < Math.min(snapshotIndex, size); i++) {
            baselineBacklog = Math.max(baselineBacklog, backlog[i]);
        }
        double beforeSeconds = Math.max(1, Math.min(snapshotIndex, size));
        double judgedPerSecondBefore = sum(judged, 0, Math.min(snapshotIndex, size)) / beforeSeconds;
        double backlogTolerance = Math.max(1.0, judgedPerSecondBefore * thresholds.backlogToleranceSeconds());

        // The rollback itself holds batch-1 for the fault pause, so every mode starts the recovery with the
        // backlog that pause built. Growth is measured from there - or from the baseline, if that is higher
        // - so a mode that only drains the injector's backlog is not reported as growing one.
        long backlogAtFault = faultIndex - 1 >= 0 && faultIndex - 1 < size ? backlog[faultIndex - 1] : baselineBacklog;
        long growthReference = Math.max(baselineBacklog, backlogAtFault);

        long peakBacklog = Long.MIN_VALUE;
        int peakIndex = -1;
        for (int i = Math.max(0, faultIndex); i < size; i++) {
            if (backlog[i] > peakBacklog) {
                peakBacklog = backlog[i];
                peakIndex = i;
            }
        }
        Long backlogDrainedAt = null;
        if (peakIndex >= 0) {
            if (peakBacklog <= baselineBacklog) {
                backlogDrainedAt = run.faultAtMs();
            } else {
                for (int i = peakIndex; i < size; i++) {
                    if (backlog[i] <= baselineBacklog) {
                        backlogDrainedAt = (fromSecond + i) * 1000L;
                        break;
                    }
                }
            }
        }

        // The recovery window closes when every sign of the fault is gone: the tail is back and the
        // backlog is down to its baseline. Whichever of the two never happened keeps it open to the end.
        long recoveredAt = run.faultAtMs() + thresholds.minDuringSeconds() * 1000L;
        boolean recovered = true;
        if (lostTotal > 0L) {
            if (tailReturnedAt == null) {
                recovered = false;
            } else {
                recoveredAt = Math.max(recoveredAt, tailReturnedAt);
            }
        }
        if (backlogDrainedAt == null) {
            recovered = false;
        } else {
            recoveredAt = Math.max(recoveredAt, backlogDrainedAt);
        }
        if (!recovered) {
            recoveredAt = run.measureToMs();
        }
        recoveredAt = Math.min(recoveredAt, run.measureToMs());
        int recoveredIndex = (int) (Math.floorDiv(recoveredAt, 1000L) - fromSecond);

        // --- stall: judged results arriving, none of them reaching the scoreboard -------------------
        int longestStall = 0;
        int totalStall = 0;
        int currentStall = 0;
        Long stallStartedAt = null;
        Long longestStallStartedAt = null;
        for (int i = Math.max(0, faultIndex); i <= Math.min(recoveredIndex, size - 1); i++) {
            if (judged[i] > 0 && newApplied[i] == 0) {
                if (currentStall == 0) {
                    stallStartedAt = (fromSecond + i) * 1000L;
                }
                currentStall++;
                totalStall++;
                if (currentStall > longestStall) {
                    longestStall = currentStall;
                    longestStallStartedAt = stallStartedAt;
                }
            } else {
                currentStall = 0;
            }
        }

        // --- tail samples at second resolution, for the series -------------------------------------
        String[] tailPresent = new String[size];
        for (TailSample sample : tailSamples) {
            int index = bin(sample.atMs(), fromSecond, size);
            if (index >= 0) {
                tailPresent[index] = Long.toString(sample.present());
            }
        }

        // --- phases -----------------------------------------------------------------------------------
        List<Second> seconds = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long second = fromSecond + i;
            seconds.add(new Second(second, second - Math.floorDiv(run.faultAtMs(), 1000L),
                    phase(second * 1000L, run, recoveredAt), submitOk[i], submitKo[i], judged[i], firstApplied[i],
                    newApplied[i], reconsumedPerSecond[i], backlog[i], tailPresent[i] == null ? "" : tailPresent[i]));
        }

        // --- metrics ----------------------------------------------------------------------------------
        metrics.put("preRollbackOffset", Long.toString(h));
        metrics.put("T_measureFrom", Long.toString(run.measureFromMs()));
        metrics.put("T_snapshot", Long.toString(run.snapshotAtMs()));
        metrics.put("T_rollback", Long.toString(run.rollbackAtMs()));
        metrics.put("T_fault", Long.toString(run.faultAtMs()));
        metrics.put("T_measureTo", Long.toString(run.measureToMs()));
        recoveryInstants(run, recoveryRows, metrics);
        metrics.put("T_new_resumed", instant(newResumedAt));
        metrics.put("newResumedAfterFaultMs", since(newResumedAt, run.faultAtMs()));
        metrics.put("lostCount", lostTotal < 0 ? UNAVAILABLE : Long.toString(lostTotal));
        metrics.put("T_tail_returned", instant(tailReturnedAt));
        metrics.put("tailReturnedAfterFaultMs", since(tailReturnedAt, run.faultAtMs()));
        metrics.put("baselineBacklog", Long.toString(baselineBacklog));
        metrics.put("backlogTolerance", format(backlogTolerance));
        metrics.put("maxBacklogAfterFault", peakIndex < 0 ? UNAVAILABLE : Long.toString(peakBacklog));
        metrics.put("T_max_backlog", peakIndex < 0 ? UNAVAILABLE : Long.toString((fromSecond + peakIndex) * 1000L));
        metrics.put("T_backlog_drained", instant(backlogDrainedAt));
        metrics.put("backlogDrainedAfterFaultMs", since(backlogDrainedAt, run.faultAtMs()));
        metrics.put("T_recovered", Long.toString(recoveredAt));
        metrics.put("recoveredWithinMeasurement", Boolean.toString(recovered));
        metrics.put("newApplyStallTotalSeconds", Integer.toString(totalStall));
        metrics.put("newApplyStallLongestSeconds", Integer.toString(longestStall));
        metrics.put("T_longest_stall_start", instant(longestStallStartedAt));
        metrics.put("reconsumedAfterFault", Long.toString(reconsumed.size()));
        metrics.put("liveRowsTotal", Long.toString(liveRows.size() - seedRows));
        metrics.put("liveSubmissionsApplied", Long.toString(first.size()));
        metrics.put("judgedLiveTotal", Long.toString(live.size()));
        long neverApplied = live.stream().filter(row -> !first.containsKey(row.submissionId())).count();
        metrics.put("judgedLiveNeverApplied", Long.toString(neverApplied));

        Map<String, PhaseFigures> phases = new LinkedHashMap<>();
        phases.put("before", phaseFigures(run.measureFromMs(), run.snapshotAtMs(), false, run, fromSecond, size,
                submitOk, submitKo, judged, firstApplied, live, first));
        phases.put("tail", phaseFigures(run.snapshotAtMs(), run.faultAtMs(), false, run, fromSecond, size,
                submitOk, submitKo, judged, firstApplied, live, first));
        phases.put("during", phaseFigures(run.faultAtMs(), recoveredAt, true, run, fromSecond, size,
                submitOk, submitKo, judged, newApplied, live, first));
        phases.put("after", phaseFigures(recoveredAt, run.measureToMs(), true, run, fromSecond, size,
                submitOk, submitKo, judged, newApplied, live, first));
        for (Map.Entry<String, PhaseFigures> entry : phases.entrySet()) {
            entry.getValue().writeTo(entry.getKey(), metrics);
        }

        metrics.put("liveRowsOutsideContest", Long.toString(outsideContest));
        if (seedRows > 0L) {
            // Only a delivery that applies seeded rows through the live path has any; the Stream never does.
            metrics.put("liveRowsSeed", Long.toString(seedRows));
        }
        metrics.put("backlogAtFault", Long.toString(backlogAtFault));
        verdict(thresholds, phases, longestStall, peakIndex < 0 ? null : peakBacklog, growthReference,
                backlogTolerance, metrics);
        return new Result(metrics, seconds);
    }

    // --- the verdict ---------------------------------------------------------------------------------

    /**
     * (C) any stop, lost throughput or growing backlog; (B) none of those but a slower reflect; (A) no
     * observable change. Checked in that order so a run is never called "slower" when it stopped.
     */
    private static void verdict(Thresholds thresholds,
                                Map<String, PhaseFigures> phases,
                                int longestStall,
                                Long peakBacklog,
                                long growthReference,
                                double backlogTolerance,
                                Map<String, String> metrics) {
        PhaseFigures before = phases.get("before");
        PhaseFigures during = phases.get("during");
        List<String> reasons = new ArrayList<>();
        if (longestStall >= thresholds.stallSeconds()) {
            reasons.add("new applies stopped for " + longestStall + "s while results were judged");
        }
        Double ratio = during.judged > 0 ? (double) during.applied / during.judged : null;
        metrics.put("duringThroughputRatio", ratio == null ? UNAVAILABLE : format(ratio));
        if (ratio != null && ratio < 1.0 - thresholds.throughputTolerance()) {
            reasons.add("new applies were " + format(ratio) + " of judged results during recovery");
        }
        if (peakBacklog != null && peakBacklog - growthReference > backlogTolerance) {
            reasons.add("backlog grew to " + peakBacklog + " from " + growthReference + " at the fault");
        }
        String verdict;
        if (!reasons.isEmpty()) {
            verdict = "C";
        } else {
            Long beforeP95 = before.latencyPercentile(95);
            Long duringP95 = during.latencyPercentile(95);
            if (beforeP95 != null && duringP95 != null
                    && duringP95 > beforeP95 * (1.0 + thresholds.latencyTolerance())) {
                verdict = "B";
                reasons.add("p95 reflect latency " + duringP95 + "ms against " + beforeP95 + "ms before");
            } else if (beforeP95 == null || duringP95 == null) {
                verdict = UNAVAILABLE;
                reasons.add("no reflect-latency sample in the before or during window");
            } else {
                verdict = "A";
            }
        }
        metrics.put("verdict", verdict);
        metrics.put("verdictReasons", String.join("; ", reasons));
        metrics.put("threshold.stallSeconds", Integer.toString(thresholds.stallSeconds()));
        metrics.put("threshold.throughputTolerance", format(thresholds.throughputTolerance()));
        metrics.put("threshold.backlogToleranceSeconds", format(thresholds.backlogToleranceSeconds()));
        metrics.put("threshold.latencyTolerance", format(thresholds.latencyTolerance()));
        metrics.put("threshold.minDuringSeconds", Integer.toString(thresholds.minDuringSeconds()));
    }

    // --- recovery trace -------------------------------------------------------------------------------

    private static void recoveryInstants(RunEvents run, Collection<Recovery> rows, Map<String, String> metrics) {
        List<Recovery> afterRollback = rows.stream()
                .filter(row -> row.startMs() >= run.rollbackAtMs())
                .sorted((a, b) -> Long.compare(a.startMs(), b.startMs()))
                .toList();
        Recovery detected = afterRollback.stream()
                .filter(row -> row.event().equals("GAP") || row.event().equals("PASS_START")
                        || row.event().equals("PASS_SKIPPED") || row.event().equals(ROLLBACK_DETECTED))
                .findFirst()
                .orElse(null);
        metrics.put("T_detected", detected == null ? UNAVAILABLE : Long.toString(detected.startMs()));
        metrics.put("detectedBy", detected == null ? UNAVAILABLE : detectedBy(detected));
        Recovery passStart = afterRollback.stream()
                .filter(row -> row.event().equals("PASS_START"))
                .findFirst()
                .orElse(null);
        Recovery passEnd = passStart == null ? null : afterRollback.stream()
                .filter(row -> row.event().equals("PASS_END") && row.startMs() == passStart.startMs()
                        && row.thread().equals(passStart.thread()))
                .findFirst()
                .orElse(null);
        metrics.put("T_replay_start", passStart == null ? UNAVAILABLE : Long.toString(passStart.startMs()));
        metrics.put("T_replay_end", passEnd == null ? UNAVAILABLE : Long.toString(passEnd.endMs()));
        metrics.put("replayDurationMs", passEnd == null ? UNAVAILABLE
                : Long.toString(passEnd.endMs() - passStart.startMs()));
        metrics.put("replayThread", passStart == null ? UNAVAILABLE : passStart.thread());
        metrics.put("replayPassKind", passStart == null ? UNAVAILABLE : passStart.detail());
        metrics.put("replayOutcome", passEnd == null ? UNAVAILABLE : passEnd.outcome());
        metrics.put("passesAfterRollback", Long.toString(afterRollback.stream()
                .filter(row -> row.event().equals("PASS_START")).count()));
        metrics.put("passesSkippedAfterRollback", Long.toString(afterRollback.stream()
                .filter(row -> row.event().equals("PASS_SKIPPED")).count()));
        metrics.put("gapQuestionsAfterRollback", Long.toString(afterRollback.stream()
                .filter(row -> row.event().equals("GAP")).count()));

        if (passStart == null) {
            metrics.put("replayChunks", UNAVAILABLE);
            return;
        }
        long end = passEnd == null ? Long.MAX_VALUE : passEnd.endMs();
        List<Recovery> chunks = afterRollback.stream()
                .filter(row -> row.event().equals("CHUNK") && row.thread().equals(passStart.thread())
                        && row.startMs() >= passStart.startMs() && row.endMs() <= end)
                .toList();
        metrics.put("replayChunks", Integer.toString(chunks.size()));
        metrics.put("replayRows", Long.toString(chunks.stream().mapToLong(Recovery::rows).sum()));
        List<Long> waits = chunks.stream().filter(row -> row.lockedAtMs() >= 0)
                .map(row -> row.lockedAtMs() - row.startMs()).toList();
        List<Long> holds = chunks.stream().filter(row -> row.lockedAtMs() >= 0)
                .map(row -> row.endMs() - row.lockedAtMs()).toList();
        metrics.put("chunkLockWaitP50Ms", optional(percentile(waits, 50)));
        metrics.put("chunkLockWaitMaxMs", optional(percentile(waits, 100)));
        metrics.put("chunkHoldP50Ms", optional(percentile(holds, 50)));
        metrics.put("chunkHoldMaxMs", optional(percentile(holds, 100)));
    }

    /**
     * The event and its thread; for the {@code mysql-poll} detector also the check that found the rollback,
     * which its record carries as the outcome, because that delivery's threads are a shared pool.
     */
    private static String detectedBy(Recovery detected) {
        if (detected.event().equals(ROLLBACK_DETECTED)) {
            return detected.event() + " (" + detected.outcome() + ") on " + detected.thread();
        }
        return detected.event() + " on " + detected.thread();
    }

    // --- phases ---------------------------------------------------------------------------------------

    private static final class PhaseFigures {
        long seconds;
        long submitOk;
        long submitKo;
        long judged;
        long applied;
        final List<Long> latencies = new ArrayList<>();
        long unapplied;

        Long latencyPercentile(int p) {
            return percentile(latencies, p);
        }

        void writeTo(String name, Map<String, String> metrics) {
            metrics.put(name + ".seconds", Long.toString(seconds));
            metrics.put(name + ".submitOkPerSecond", rate(submitOk));
            metrics.put(name + ".submitKoPerSecond", rate(submitKo));
            metrics.put(name + ".judgedPerSecond", rate(judged));
            metrics.put(name + ".appliedPerSecond", rate(applied));
            metrics.put(name + ".reflectLatencySamples", Integer.toString(latencies.size()));
            metrics.put(name + ".reflectLatencyP50Ms", optional(percentile(latencies, 50)));
            metrics.put(name + ".reflectLatencyP95Ms", optional(percentile(latencies, 95)));
            metrics.put(name + ".reflectLatencyP99Ms", optional(percentile(latencies, 99)));
            metrics.put(name + ".reflectLatencyMaxMs", optional(percentile(latencies, 100)));
            metrics.put(name + ".judgedNeverApplied", Long.toString(unapplied));
        }

        private String rate(long count) {
            return seconds <= 0 ? UNAVAILABLE : format((double) count / seconds);
        }
    }

    /**
     * @param newOnly whether only events above {@code H} count - true for every window from the fault on
     */
    private static PhaseFigures phaseFigures(long fromMs, long toMs, boolean newOnly, RunEvents run,
                                             long fromSecond, int size,
                                             long[] submitOk, long[] submitKo, long[] judged, long[] applied,
                                             List<Judged> live, Map<Long, LiveApply> first) {
        PhaseFigures figures = new PhaseFigures();
        int from = Math.max(0, (int) (Math.floorDiv(fromMs, 1000L) - fromSecond));
        int to = Math.min(size, (int) (Math.floorDiv(toMs, 1000L) - fromSecond));
        figures.seconds = Math.max(0, to - from);
        figures.submitOk = sum(submitOk, from, to);
        figures.submitKo = sum(submitKo, from, to);
        figures.judged = sum(judged, from, to);
        figures.applied = sum(applied, from, to);
        for (Judged row : live) {
            if (row.judgedAtMs() < fromMs || row.judgedAtMs() >= toMs) {
                continue;
            }
            LiveApply applyRow = first.get(row.submissionId());
            if (applyRow == null) {
                figures.unapplied++;
                continue;
            }
            if (newOnly && applyRow.offset() <= run.preRollbackOffset()) {
                continue;
            }
            figures.latencies.add(applyRow.appliedAtMs() - row.judgedAtMs());
        }
        return figures;
    }

    private static String phase(long atMs, RunEvents run, long recoveredAt) {
        if (atMs < run.snapshotAtMs()) {
            return "before";
        }
        if (atMs < run.faultAtMs()) {
            return "tail";
        }
        return atMs < recoveredAt ? "during" : "after";
    }

    // --- helpers --------------------------------------------------------------------------------------

    private static void reconsumedIfAfterFault(LiveApply row, RunEvents run, long h, List<LiveApply> into) {
        if (row.offset() <= h && row.appliedAtMs() >= run.faultAtMs()) {
            into.add(row);
        }
    }

    private static int bin(long atMs, long fromSecond, int size) {
        long index = Math.floorDiv(atMs, 1000L) - fromSecond;
        return index < 0 || index >= size ? -1 : (int) index;
    }

    private static long sum(long[] values, int from, int to) {
        long total = 0L;
        for (int i = Math.max(0, from); i < Math.min(values.length, to); i++) {
            total += values[i];
        }
        return total;
    }

    /** Nearest-rank percentile, or {@code null} for no samples. {@code p = 100} is the maximum. */
    static Long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static String instant(Long atMs) {
        return atMs == null ? UNAVAILABLE : Long.toString(atMs);
    }

    private static String since(Long atMs, long fromMs) {
        return atMs == null ? UNAVAILABLE : Long.toString(atMs - fromMs);
    }

    private static String optional(Long value) {
        return value == null ? UNAVAILABLE : Long.toString(value);
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
