package my.oj.web.contest.scoreboard.poll;

import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.LiveEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryRecord;
import my.oj.web.contest.scoreboard.experiment.RecordingExperimentTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static my.oj.web.submission.SubmissionResult.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the {@code mysql-poll} delivery reports to the live-impact experiment trace: every chunk Redis
 * applied as a live batch, the detection with the check that found it, and a range recovery as a pass.
 */
class ContestScoreboardMySqlPollTraceTests {

    private final RecordingExperimentTrace trace = new RecordingExperimentTrace();

    private PollFixture fixture(int batchSize, int chunkSize) {
        return new PollFixture(new InMemorySequencedScoreboard(), new InMemorySequenceLedger(), batchSize,
                chunkSize, 100, trace);
    }

    private static List<Long> offsets(List<RecordingExperimentTrace.LiveBatch> batches) {
        return batches.stream().flatMap(batch -> batch.events().stream()).map(LiveEvent::offset).toList();
    }

    @Test
    void eachPolledChunkIsOneLiveBatchCarryingTheSequencesIssued() {
        PollFixture fixture = fixture(2, 2);
        LongStream.rangeClosed(1, 3).forEach(id -> fixture.ledger.judged(id, ACCEPTED));

        fixture.poller.pollOnce();

        assertThat(trace.liveBatches()).hasSize(2);
        assertThat(trace.liveBatches().get(0).events()).extracting(LiveEvent::submissionId).containsExactly(1L, 2L);
        assertThat(offsets(trace.liveBatches())).containsExactly(1L, 2L, 3L);
        assertThat(trace.liveBatches().get(0).events()).allSatisfy(event -> assertThat(event.judgedAt()).isNull());
        assertThat(trace.recoveryRecords()).as("no rollback, no recovery record").isEmpty();
    }

    /**
     * Rows 1..8 applied under 1..8, Redis restored to allocator 3. The poll that follows finds the rollback
     * before its batch; the recovery pass then re-applies 4..8 and reports each under the sequence it held
     * inside the range - the offset a re-read Stream event would carry - while new row 9 is issued 9.
     */
    @Test
    void aRollbackIsReportedWithItsCheckAndTheRangeRecoveryAsAPass() {
        PollFixture fixture = fixture(100, 2);
        LongStream.rangeClosed(1, 3).forEach(id -> fixture.ledger.judged(id, ACCEPTED));
        fixture.poller.pollOnce();
        InMemorySequencedScoreboard.Snapshot snapshot = fixture.scoreboard.snapshot();
        LongStream.rangeClosed(4, 8).forEach(id -> fixture.ledger.judged(id, ACCEPTED));
        fixture.poller.pollOnce();
        fixture.scoreboard.restore(snapshot);
        int batchesBefore = trace.liveBatches().size();

        fixture.ledger.judged(9, ACCEPTED);
        fixture.poller.pollOnce();

        List<RecoveryRecord> detections = trace.recoveryRecords().stream()
                .filter(record -> record.event() == RecoveryEvent.ROLLBACK_DETECTED).toList();
        assertThat(detections).hasSize(1);
        RecoveryRecord detected = detections.get(0);
        assertThat(detected.outcome()).isEqualTo("poll-batch");
        assertThat(detected.detail()).startsWith("(3, 8] generation ");
        assertThat(detected.startEpochMillis()).isPositive().isLessThanOrEqualTo(detected.endEpochMillis());
        List<RecordingExperimentTrace.LiveBatch> afterFault = trace.liveBatches().subList(batchesBefore,
                trace.liveBatches().size());
        assertThat(offsets(afterFault)).as("the new row is issued a sequence above the fenced watermark")
                .containsExactly(9L);

        int batchesBeforeRecovery = trace.liveBatches().size();
        fixture.recovery.recoverPending();

        List<RecoveryEvent> passEvents = trace.recoveryEvents().stream()
                .filter(event -> event != RecoveryEvent.ROLLBACK_DETECTED).toList();
        assertThat(passEvents).containsExactly(RecoveryEvent.PASS_START, RecoveryEvent.CHUNK, RecoveryEvent.CHUNK,
                RecoveryEvent.CHUNK, RecoveryEvent.PASS_END);
        List<RecoveryRecord> pass = trace.recoveryRecords().stream()
                .filter(record -> record.event() != RecoveryEvent.ROLLBACK_DETECTED).toList();
        assertThat(pass.get(0).detail()).startsWith("range-recovery (3, 8] generation ");
        assertThat(pass.get(4).outcome()).isEqualTo("COMPLETED");
        assertThat(pass.get(4).startEpochMillis()).isEqualTo(pass.get(0).startEpochMillis());
        assertThat(pass.subList(1, 4)).extracting(RecoveryRecord::rows).containsExactly(2, 2, 1);
        assertThat(pass.subList(1, 4)).allSatisfy(chunk -> assertThat(chunk.lockedAtEpochMillis())
                .isGreaterThanOrEqualTo(chunk.startEpochMillis()));
        List<RecordingExperimentTrace.LiveBatch> recovered = trace.liveBatches().subList(batchesBeforeRecovery,
                trace.liveBatches().size());
        assertThat(offsets(recovered)).containsExactlyInAnyOrder(4L, 5L, 6L, 7L, 8L);
        LongStream.rangeClosed(4, 8).forEach(id -> assertThat(fixture.ledger.sequenceOf(id)).isGreaterThan(9L));
    }

    @Test
    void theTriggerOfEachCheckIsReported() {
        PollFixture fixture = fixture(100, 100);
        fixture.scoreboard.fenceAllocator(3);
        fixture.ledger.watermark = 9;

        fixture.detector.check("periodic");

        assertThat(trace.recoveryRecords()).singleElement().satisfies(record -> {
            assertThat(record.event()).isEqualTo(RecoveryEvent.ROLLBACK_DETECTED);
            assertThat(record.outcome()).isEqualTo("periodic");
            assertThat(record.detail()).endsWith("fenced to 9");
        });
    }

    @Test
    void aDisabledTraceIsNeverAskedToRecord() {
        ContestScoreboardExperimentTrace refusing = new ContestScoreboardExperimentTrace() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public void liveBatchApplied(long appliedAtEpochMillis, List<LiveEvent> events) {
                throw new AssertionError("recorded while disabled");
            }

            @Override
            public void recovery(RecoveryRecord record) {
                throw new AssertionError("recorded while disabled");
            }
        };
        PollFixture fixture = new PollFixture(new InMemorySequencedScoreboard(), new InMemorySequenceLedger(), 100,
                2, 100, refusing);
        LongStream.rangeClosed(1, 4).forEach(id -> fixture.ledger.judged(id, ACCEPTED));
        fixture.poller.pollOnce();
        fixture.scoreboard.restore(new InMemorySequencedScoreboard().snapshot());
        fixture.detector.check("periodic");

        fixture.recovery.recoverPending();

        assertThat(fixture.ledger.pendingRanges()).isEmpty();
    }
}
