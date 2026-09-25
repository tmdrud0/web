package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactRecords.Judged;
import my.oj.perf.liveimpact.LiveImpactRecords.LiveApply;
import my.oj.perf.liveimpact.LiveImpactRecords.Request;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Twenty results a second for sixty seconds, applied at a rate the scenario chooses. */
class LiveImpactCalibrationTests {

    private static final long T0 = 1_700_000_000_000L;
    private static final long TO = T0 + 60_000L;

    @Test
    void aRateThePipelineKeepsUpWithIsStable() {
        Map<String, String> metrics = analyze(50L, 20.0, 0);

        assertEquals("stable", metrics.get("calibration.verdict"), metrics.get("calibration.reasons"));
        assertEquals("20.000", metrics.get("calibration.judgedPerSecond"));
    }

    /** Applied at three quarters of the judged rate: the backlog grows by about five a second. */
    @Test
    void aRateThePipelineFallsBehindIsBacklogGrowing() {
        Map<String, String> metrics = analyze(-1L, 20.0, 0);

        assertEquals("backlog-growing", metrics.get("calibration.verdict"));
        assertEquals(5.0, Double.parseDouble(metrics.get("calibration.backlogSlopePerSecond")), 0.2);
    }

    @Test
    void refusedSubmissionsAboveTheToleranceAreKo() {
        Map<String, String> metrics = analyze(50L, 20.0, 60);

        assertEquals("ko", metrics.get("calibration.verdict"));
    }

    @Test
    void aLoadGeneratorThatFellShortOfTheTargetIsUnderTarget() {
        Map<String, String> metrics = analyze(50L, 40.0, 0);

        assertEquals("under-target", metrics.get("calibration.verdict"));
    }

    @Test
    void theSlopeOfAStraightLineIsItsGradient() {
        assertEquals(2.0, LiveImpactCalibration.slope(new double[]{1, 3, 5, 7}), 1e-9);
        assertEquals(0.0, LiveImpactCalibration.slope(new double[]{4}), 1e-9);
    }

    /**
     * @param latencyMs apply latency, or {@code -1} to apply only three results in four
     * @param koCount   submissions answered KO, spread over the window
     */
    private static Map<String, String> analyze(long latencyMs, double targetRps, int koCount) {
        List<LiveApply> live = new ArrayList<>();
        List<Judged> judged = new ArrayList<>();
        List<Request> requests = new ArrayList<>();
        for (int k = 0; k < 1_200; k++) {
            long judgedAt = T0 + k * 50L;
            judged.add(new Judged(k, judgedAt, false));
            requests.add(new Request("api-contest-submit", judgedAt - 20L, judgedAt - 10L, true));
            if (latencyMs >= 0) {
                live.add(new LiveApply(judgedAt + latencyMs, k, k, judgedAt));
            } else if (k % 4 != 3) {
                live.add(new LiveApply(judgedAt + 50L, k, k, judgedAt));
            }
        }
        for (int i = 0; i < koCount; i++) {
            requests.add(new Request("api-contest-submit", T0 + i * 900L, T0 + i * 900L + 5L, false));
        }
        return LiveImpactCalibration.analyze(T0, TO, targetRps, LiveImpactCalibration.Thresholds.defaults(),
                live, judged, requests);
    }
}
