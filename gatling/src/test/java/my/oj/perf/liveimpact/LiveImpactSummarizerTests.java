package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactAnalysis.Result;
import my.oj.perf.liveimpact.LiveImpactAnalysis.Thresholds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One run directory in the layout the runner leaves, read end to end. */
class LiveImpactSummarizerTests {

    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path runDir;

    @Test
    void aRunDirectoryIsSummarizedIntoTheThreeOutputFiles() throws IOException {
        write("run-events.properties", String.join("\n",
                "measureFromMs=" + T0,
                "snapshotAtMs=" + (T0 + 20_000L),
                "rollbackAtMs=" + (T0 + 25_000L),
                "faultAtMs=" + (T0 + 25_000L),
                "measureToMs=" + (T0 + 60_000L),
                "preRollbackOffset=249",
                "gatlingClockOffsetMs=-1000",
                "mode=full-replay",
                "requiredObservationSeconds=60",
                ""));
        StringBuilder live = new StringBuilder("appliedAtEpochMs,offset,submissionId,judgedAt,judgedAtEpochMsUtc,batchSize\n");
        StringBuilder judged = new StringBuilder("submissionId,judgedAtEpochMs,seed\n");
        for (int k = 0; k < 550; k++) {
            long judgedAt = T0 + k * 100L;
            live.append(judgedAt + 100L).append(',').append(k).append(',').append(1_000 + k)
                    .append(",,").append(judgedAt).append(",1\n");
            judged.append(1_000 + k).append(',').append(judgedAt).append(",0\n");
        }
        write("trace/live-apply.csv", live.toString());
        write("trace/recovery-trace.csv", "event,thread,startEpochMs,lockedAtEpochMs,endEpochMs,rows,detail,outcome\n");
        write("trace/trace-status.properties", "droppedLiveEvents=0\nwriteFailures=0\n");
        write("judged.csv", judged.toString());
        write("tail-poll.csv", "atEpochMicros,present,total\n" + (T0 + 26_000L) * 1000L + ",3,3\n");
        write("gatling/contestsubmissionsimulation-1/simulation.log",
                "REQUEST\t\tapi-contest-submit\t" + (T0 + 2_000L) + "\t" + (T0 + 2_030L) + "\tOK\t \n");

        Result result = LiveImpactSummarizer.summarize(runDir, Thresholds.defaults(), "api-contest-submit");

        assertEquals("A", result.verdict());
        assertEquals("true", result.metrics().get("traceComplete"));
        assertEquals("full-replay", result.metrics().get("run.mode"));
        assertEquals("1", result.metrics().get("gatling.submitOk"));
        assertEquals("1000", result.metrics().get("tailReturnedAfterFaultMs"));
        // Recovered ten seconds after the fault (the minimum window), and the load ran 25 s past it.
        assertEquals("25", result.metrics().get("observedAfterRecoverySeconds"));
        assertEquals("false", result.metrics().get("observationSufficient"));
        List<String> series = Files.readAllLines(runDir.resolve("live-impact-timeseries.csv"));
        assertEquals(62, series.size());
        // The request ended at T0+2030 on the Windows clock, one second ahead of the container.
        assertTrue(series.get(2).startsWith((T0 / 1000L + 1L) + ",-24,before,1,0,"), series.get(2));
        assertTrue(Files.readString(runDir.resolve("live-impact-summary.csv")).contains("verdict,A\n"));
        assertTrue(Files.readString(runDir.resolve("live-impact-summary.md")).contains("Verdict: **A**"));
    }

    private void write(String relative, String content) throws IOException {
        Path path = runDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
