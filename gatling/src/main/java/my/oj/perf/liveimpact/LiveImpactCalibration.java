package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactRecords.Judged;
import my.oj.perf.liveimpact.LiveImpactRecords.LiveApply;
import my.oj.perf.liveimpact.LiveImpactRecords.Request;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether one inflow rate is sustainable with no recovery running: the calibration phase.
 *
 * <p>The window is a steady stretch of load with nothing injected. The rate is sustainable when the
 * load generator's submissions were accepted, the achieved rate is near the target, and the backlog -
 * judged results not yet applied, over the load's own submissions - did not keep growing. The slope is
 * a least-squares fit over the per-second backlog, so one noisy second does not decide it.</p>
 */
final class LiveImpactCalibration {

    /**
     * @param koTolerance       submissions refused or failed, as a fraction of all, above which the rate is
     *                          not sustainable
     * @param slopeTolerance    backlog growth, as a fraction of the judged rate per second, above which the
     *                          backlog is growing
     * @param achievedTolerance achieved submissions below {@code (1 - this)} of the target is under target
     */
    record Thresholds(double koTolerance, double slopeTolerance, double achievedTolerance) {

        static Thresholds defaults() {
            return new Thresholds(0.01, 0.01, 0.05);
        }
    }

    private LiveImpactCalibration() {
    }

    static Map<String, String> analyze(long fromMs,
                                       long toMs,
                                       double targetRps,
                                       Thresholds thresholds,
                                       Collection<LiveApply> liveRows,
                                       Collection<Judged> judgedRows,
                                       Collection<Request> submitRequests) {
        if (toMs <= fromMs) {
            throw new IllegalArgumentException("Calibration window is empty: " + fromMs + ".." + toMs);
        }
        long fromSecond = Math.floorDiv(fromMs, 1000L);
        int size = (int) (Math.floorDiv(toMs, 1000L) - fromSecond);
        long[] judged = new long[size];
        long[] applied = new long[size];
        long ok = 0L;
        long ko = 0L;
        for (Request request : submitRequests) {
            if (request.endMs() < fromMs || request.endMs() >= toMs) {
                continue;
            }
            if (request.ok()) {
                ok++;
            } else {
                ko++;
            }
        }
        long judgedBefore = 0L;
        for (Judged row : judgedRows) {
            if (row.seed()) {
                continue;
            }
            long index = Math.floorDiv(row.judgedAtMs(), 1000L) - fromSecond;
            if (index < 0) {
                judgedBefore++;
            } else if (index < size) {
                judged[(int) index]++;
            }
        }
        java.util.Set<Long> contestSubmissions = new java.util.HashSet<>();
        // Seeded results are not in the judged side, so they stay out of the applied side too: the
        // mysql-poll delivery applies them through the live path, before the window.
        java.util.Set<Long> seedSubmissions = new java.util.HashSet<>();
        for (Judged row : judgedRows) {
            contestSubmissions.add(row.submissionId());
            if (row.seed()) {
                seedSubmissions.add(row.submissionId());
            }
        }
        Map<Long, Long> first = new HashMap<>();
        for (LiveApply row : liveRows) {
            if (seedSubmissions.contains(row.submissionId())) {
                continue;
            }
            if (contestSubmissions.isEmpty() || contestSubmissions.contains(row.submissionId())) {
                first.merge(row.submissionId(), row.appliedAtMs(), Math::min);
            }
        }
        long appliedBefore = 0L;
        for (long at : first.values()) {
            long index = Math.floorDiv(at, 1000L) - fromSecond;
            if (index < 0) {
                appliedBefore++;
            } else if (index < size) {
                applied[(int) index]++;
            }
        }

        double[] backlog = new double[size];
        long cumulativeJudged = judgedBefore;
        long cumulativeApplied = appliedBefore;
        long judgedTotal = 0L;
        long appliedTotal = 0L;
        double maxBacklog = 0d;
        for (int i = 0; i < size; i++) {
            cumulativeJudged += judged[i];
            cumulativeApplied += applied[i];
            judgedTotal += judged[i];
            appliedTotal += applied[i];
            backlog[i] = cumulativeJudged - cumulativeApplied;
            maxBacklog = Math.max(maxBacklog, backlog[i]);
        }
        double slope = slope(backlog);
        double submitRate = (double) ok / size;
        double judgedRate = (double) judgedTotal / size;
        double appliedRate = (double) appliedTotal / size;
        double koRatio = ok + ko == 0 ? 1d : (double) ko / (ok + ko);

        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("calibration.seconds", Integer.toString(size));
        metrics.put("calibration.targetRps", LiveImpactAnalysis.format(targetRps));
        metrics.put("calibration.submitOkPerSecond", LiveImpactAnalysis.format(submitRate));
        metrics.put("calibration.submitKo", Long.toString(ko));
        metrics.put("calibration.koRatio", LiveImpactAnalysis.format(koRatio));
        metrics.put("calibration.judgedPerSecond", LiveImpactAnalysis.format(judgedRate));
        metrics.put("calibration.appliedPerSecond", LiveImpactAnalysis.format(appliedRate));
        metrics.put("calibration.backlogStart", LiveImpactAnalysis.format(backlog[0]));
        metrics.put("calibration.backlogEnd", LiveImpactAnalysis.format(backlog[size - 1]));
        metrics.put("calibration.backlogMax", LiveImpactAnalysis.format(maxBacklog));
        metrics.put("calibration.backlogSlopePerSecond", LiveImpactAnalysis.format(slope));

        StringBuilder reasons = new StringBuilder();
        String verdict = "stable";
        if (koRatio > thresholds.koTolerance()) {
            verdict = "ko";
            reasons.append("KO ratio ").append(LiveImpactAnalysis.format(koRatio)).append("; ");
        }
        if (slope > Math.max(1d, judgedRate * thresholds.slopeTolerance())) {
            verdict = verdict.equals("stable") ? "backlog-growing" : verdict;
            reasons.append("backlog grows ").append(LiveImpactAnalysis.format(slope)).append("/s; ");
        }
        if (targetRps > 0 && submitRate < targetRps * (1d - thresholds.achievedTolerance())) {
            verdict = verdict.equals("stable") ? "under-target" : verdict;
            reasons.append("achieved ").append(LiveImpactAnalysis.format(submitRate)).append("/s of ")
                    .append(LiveImpactAnalysis.format(targetRps)).append("/s; ");
        }
        metrics.put("calibration.verdict", verdict);
        metrics.put("calibration.reasons", reasons.toString().trim());
        metrics.put("calibration.threshold.koTolerance", LiveImpactAnalysis.format(thresholds.koTolerance()));
        metrics.put("calibration.threshold.slopeTolerance", LiveImpactAnalysis.format(thresholds.slopeTolerance()));
        metrics.put("calibration.threshold.achievedTolerance", LiveImpactAnalysis.format(thresholds.achievedTolerance()));
        return metrics;
    }

    /** Least-squares slope of the series against its index, in units per second. */
    static double slope(double[] values) {
        int n = values.length;
        if (n < 2) {
            return 0d;
        }
        double meanX = (n - 1) / 2d;
        double meanY = 0d;
        for (double value : values) {
            meanY += value;
        }
        meanY /= n;
        double numerator = 0d;
        double denominator = 0d;
        for (int i = 0; i < n; i++) {
            numerator += (i - meanX) * (values[i] - meanY);
            denominator += (i - meanX) * (i - meanX);
        }
        return numerator / denominator;
    }
}
