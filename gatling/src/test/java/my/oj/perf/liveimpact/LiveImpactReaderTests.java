package my.oj.perf.liveimpact;

import my.oj.perf.liveimpact.LiveImpactRecords.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveImpactReaderTests {

    /**
     * Gatling 3.10's text log, with and without a group, and with the Windows clock moved into the
     * container frame by the measured offset.
     */
    @Test
    void gatlingRequestsAreReadByNameAndShiftedByTheClockOffset() throws IOException {
        String log = String.join("\n",
                "RUN\tmy.oj.perf.ContestSubmissionSimulation\tcontestsubmissionsimulation\t1700000000000\t \t3.10.5",
                "USER\tContest submissions (API)\tSTART\t1700000000100",
                "REQUEST\t\tapi-login-once\t1700000000100\t1700000000150\tOK\t ",
                "REQUEST\t\tapi-contest-submit\t1700000001000\t1700000001040\tOK\t ",
                "REQUEST\tgroup-a\tapi-contest-submit\t1700000002000\t1700000002500\tKO\tstatus.find.is(202), but actually found 429",
                "");

        List<Request> requests = LiveImpactReader.gatlingRequests(new StringReader(log), "api-contest-submit", -250L);

        assertEquals(2, requests.size());
        assertEquals(new Request("api-contest-submit", 1700000000750L, 1700000000790L, true), requests.get(0));
        assertFalse(requests.get(1).ok());
        assertEquals(1700000002250L, requests.get(1).endMs());
    }

    @Test
    void aFileWithAnotherHeaderIsRefusedRatherThanReadColumnShifted() {
        assertThrows(IllegalArgumentException.class, () ->
                LiveImpactReader.liveApplies(new StringReader("offset,submissionId\n1,2\n")));
    }

    @Test
    void anAbsentFileIsNoRows() throws IOException {
        assertTrue(LiveImpactReader.liveApplies(new StringReader("")).isEmpty());
    }

    @Test
    void recoveryRowsKeepQuotedCommas() throws IOException {
        String csv = "event,thread,startEpochMs,lockedAtEpochMs,endEpochMs,rows,detail,outcome\n"
                + "CHUNK,consumer-1,10,12,30,500,\"contest 5, part \"\"a\"\"\",applied\n";

        LiveImpactRecords.Recovery row = LiveImpactReader.recoveryRows(new StringReader(csv)).get(0);

        assertEquals("contest 5, part \"a\"", row.detail());
        assertEquals(500, row.rows());
    }

    @Test
    void liveRowsWithoutAJudgedTimeCarryMinusOne() throws IOException {
        String csv = "appliedAtEpochMs,offset,submissionId,judgedAt,judgedAtEpochMsUtc,batchSize\n"
                + "1000,7,107,2026-09-25T12:00:00.250,1790337600250,2\n"
                + "1000,9,109,,,2\n";

        List<LiveImpactRecords.LiveApply> rows = LiveImpactReader.liveApplies(new StringReader(csv));

        assertEquals(1790337600250L, rows.get(0).judgedAtMs());
        assertEquals(-1L, rows.get(1).judgedAtMs());
    }

    @Test
    void judgedRowsNeverJudgedAreLeftOutAndSeedIsRead() throws IOException {
        String csv = "submissionId,judgedAtEpochMs,seed\n1,1000,1\n2,,0\n3,2000,0\n";

        List<LiveImpactRecords.Judged> rows = LiveImpactReader.judged(new StringReader(csv));

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).seed());
        assertFalse(rows.get(1).seed());
    }

    @Test
    void aTraceWithAnyDropIsIncomplete() {
        assertTrue(LiveImpactSummarizer.traceComplete(Map.of("droppedLiveEvents", "0", "writeFailures", "0"), false));
        assertFalse(LiveImpactSummarizer.traceComplete(Map.of("droppedLiveEvents", "3"), false));
        assertFalse(LiveImpactSummarizer.traceComplete(Map.of(), false));
        assertFalse(LiveImpactSummarizer.traceComplete(Map.of("droppedLiveEvents", "0"), true));
    }
}
