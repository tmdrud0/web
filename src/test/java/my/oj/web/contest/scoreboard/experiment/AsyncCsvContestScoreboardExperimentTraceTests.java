package my.oj.web.contest.scoreboard.experiment;

import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.LiveEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncCsvContestScoreboardExperimentTraceTests {

    private static final LocalDateTime JUDGED_AT = LocalDateTime.of(2026, 9, 25, 12, 0, 0, 250_000_000);

    @TempDir
    Path directory;

    @Test
    void liveEventsAreWrittenOneRowEachWithTheBatchInstantAndTheJudgedTimeInBothForms() throws IOException {
        try (AsyncCsvContestScoreboardExperimentTrace trace = manual(16)) {
            trace.liveBatchApplied(1_000L, List.of(
                    new LiveEvent(7L, 107L, JUDGED_AT),
                    new LiveEvent(9L, 109L, null)));
            trace.drain();
        }

        long judgedMillis = JUDGED_AT.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertThat(lines(AsyncCsvContestScoreboardExperimentTrace.LIVE_FILE)).containsExactly(
                AsyncCsvContestScoreboardExperimentTrace.LIVE_HEADER,
                "1000,7,107,2026-09-25T12:00:00.250," + judgedMillis + ",2",
                "1000,9,109,,,2");
    }

    @Test
    void recoveryRecordsAreQuotedWhereTheirTextNeedsIt() throws IOException {
        try (AsyncCsvContestScoreboardExperimentTrace trace = manual(16)) {
            trace.recovery(new RecoveryRecord(RecoveryEvent.CHUNK, "scheduling-1", 10L, 12L, 30L, 500,
                    "contest 5, part \"a\"", "applied"));
            trace.drain();
        }

        assertThat(lines(AsyncCsvContestScoreboardExperimentTrace.RECOVERY_FILE)).containsExactly(
                AsyncCsvContestScoreboardExperimentTrace.RECOVERY_HEADER,
                "CHUNK,scheduling-1,10,12,30,500,\"contest 5, part \"\"a\"\"\",applied");
    }

    /** A JVM restarted inside one run continues the files instead of truncating the first JVM's rows. */
    @Test
    void reopeningAppendsWithoutASecondHeader() throws IOException {
        try (AsyncCsvContestScoreboardExperimentTrace trace = manual(16)) {
            trace.liveBatchApplied(1L, List.of(new LiveEvent(1L, 101L, JUDGED_AT)));
        }
        try (AsyncCsvContestScoreboardExperimentTrace trace = manual(16)) {
            trace.liveBatchApplied(2L, List.of(new LiveEvent(2L, 102L, JUDGED_AT)));
        }

        List<String> lines = lines(AsyncCsvContestScoreboardExperimentTrace.LIVE_FILE);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo(AsyncCsvContestScoreboardExperimentTrace.LIVE_HEADER);
        assertThat(lines.get(1)).startsWith("1,1,101,");
        assertThat(lines.get(2)).startsWith("2,2,102,");
    }

    /**
     * A full queue drops and counts rather than blocking the caller - the caller is the path being
     * measured - and the count reaches the status file so a short trace is not read as a slow pipeline.
     */
    @Test
    void aFullQueueDropsAndCountsInsteadOfBlocking() throws IOException {
        try (AsyncCsvContestScoreboardExperimentTrace trace = manual(1)) {
            trace.liveBatchApplied(1L, List.of(new LiveEvent(1L, 101L, JUDGED_AT)));
            trace.liveBatchApplied(2L, List.of(new LiveEvent(2L, 102L, JUDGED_AT), new LiveEvent(3L, 103L, JUDGED_AT)));
            trace.recovery(new RecoveryRecord(RecoveryEvent.GAP, "t", 1L, -1L, 2L, -1, "d", "o"));

            assertThat(trace.droppedLiveEvents()).isEqualTo(2L);
            assertThat(trace.droppedRecoveryRecords()).isEqualTo(1L);
            trace.drain();
        }

        assertThat(lines(AsyncCsvContestScoreboardExperimentTrace.LIVE_FILE)).hasSize(2);
        assertThat(lines(AsyncCsvContestScoreboardExperimentTrace.STATUS_FILE)).contains(
                "writtenLiveEvents=1",
                "droppedLiveBatches=1",
                "droppedLiveEvents=2",
                "droppedRecoveryRecords=1",
                "writeFailures=0");
    }

    @Test
    void theWriterThreadDrainsWithoutBeingAsked() throws Exception {
        try (AsyncCsvContestScoreboardExperimentTrace trace =
                     new AsyncCsvContestScoreboardExperimentTrace(directory, 16, Duration.ofMillis(10))) {
            trace.liveBatchApplied(5L, List.of(new LiveEvent(5L, 105L, JUDGED_AT)));
            long deadline = System.currentTimeMillis() + 5_000L;
            while (lines(AsyncCsvContestScoreboardExperimentTrace.LIVE_FILE).size() < 2
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
        }

        assertThat(lines(AsyncCsvContestScoreboardExperimentTrace.LIVE_FILE)).hasSize(2);
    }

    private AsyncCsvContestScoreboardExperimentTrace manual(int capacity) {
        return new AsyncCsvContestScoreboardExperimentTrace(directory, capacity, Duration.ofSeconds(1), false);
    }

    private List<String> lines(String file) throws IOException {
        Path path = directory.resolve(file);
        return Files.exists(path) ? Files.readAllLines(path, StandardCharsets.UTF_8) : List.of();
    }
}
