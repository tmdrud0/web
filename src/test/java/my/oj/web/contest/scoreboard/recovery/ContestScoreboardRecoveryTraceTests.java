package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryEvent;
import my.oj.web.contest.scoreboard.experiment.ContestScoreboardExperimentTrace.RecoveryRecord;
import my.oj.web.contest.scoreboard.experiment.RecordingExperimentTrace;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.Outcome;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.testsupport.NoOpTransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * The recovery half of the experiment trace: a pass is recorded where it takes the gate and where it
 * gives it back, on the thread that ran it, and each replayed chunk separates waiting for the apply lock
 * from holding it.
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardRecoveryTraceTests {

    @Mock
    private ContestScoreboardApplier scoreboardApplier;
    @Mock
    private ContestScoreboardAppliedMarker appliedMarker;

    @Test
    void aPassIsRecordedAtItsStartAndItsEndOnTheThreadThatRanIt() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        ContestScoreboardRecoveryPassGate gate = new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry(), trace);
        AtomicReference<List<RecoveryEvent>> seenInside = new AtomicReference<>();

        gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            seenInside.set(trace.recoveryEvents());
            return Outcome.COVERED;
        });

        // The start is on record before the pass has done anything, so a pass that never returns still
        // says when it began.
        assertThat(seenInside.get()).containsExactly(RecoveryEvent.PASS_START);
        assertThat(trace.recoveryEvents()).containsExactly(RecoveryEvent.PASS_START, RecoveryEvent.PASS_END);
        RecoveryRecord end = trace.recoveryRecords().get(1);
        assertThat(end.detail()).isEqualTo("mysql-replay");
        assertThat(end.outcome()).isEqualTo("COVERED");
        assertThat(end.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(end.startEpochMillis()).isEqualTo(trace.recoveryRecords().get(0).startEpochMillis());
        assertThat(end.endEpochMillis()).isGreaterThanOrEqualTo(end.startEpochMillis());
    }

    @Test
    void aPassThatThrewEndsAsFailedAndAnAttemptAgainstAHeldGateIsRecordedAsSkipped() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        ContestScoreboardRecoveryPassGate gate = new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry(), trace);

        assertThatThrownBy(() -> gate.tryRun(PassKind.SEQUENCE_CHECK, () -> {
            gate.tryRun(PassKind.MYSQL_REPLAY, () -> 1);
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(trace.recoveryEvents()).containsExactly(
                RecoveryEvent.PASS_START, RecoveryEvent.PASS_SKIPPED, RecoveryEvent.PASS_END);
        assertThat(trace.recoveryRecords().get(1).detail()).isEqualTo("mysql-replay");
        assertThat(trace.recoveryRecords().get(2).outcome()).isEqualTo("failed");
    }

    @Test
    void aChunkIsRecordedWithItsRowCountAndTheInstantTheLockWasTaken() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), null))
                    .toList();
        });

        application(trace).apply(requests(), "contest 77");

        assertThat(trace.recoveryEvents()).containsExactly(RecoveryEvent.CHUNK);
        RecoveryRecord chunk = trace.recoveryRecords().get(0);
        assertThat(chunk.rows()).isEqualTo(2);
        assertThat(chunk.detail()).isEqualTo("contest 77");
        assertThat(chunk.outcome()).isEqualTo("applied");
        assertThat(chunk.lockedAtEpochMillis()).isBetween(chunk.startEpochMillis(), chunk.endEpochMillis());
    }

    @Test
    void aRefusedChunkIsRecordedAsFailedAndStillThrows() {
        RecordingExperimentTrace trace = new RecordingExperimentTrace();
        when(scoreboardApplier.applyAll(anyList())).thenReturn(List.of(
                ContestScoreboardApplier.ApplyResult.failure(3L, "refused")));

        assertThatThrownBy(() -> application(trace).apply(requests(), "contest 77"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(trace.recoveryRecords()).singleElement()
                .satisfies(chunk -> assertThat(chunk.outcome()).isEqualTo("failed"));
    }

    private ContestScoreboardReplayApplication application(RecordingExperimentTrace trace) {
        return new ContestScoreboardReplayApplication(
                scoreboardApplier,
                appliedMarker,
                new ContestScoreboardApplyLock(),
                new ContestSubmissionBatchExecutor(new NoOpTransactionManager()),
                new SimpleMeterRegistry(),
                trace
        );
    }

    private static List<ContestScoreboardApplier.ApplyRequest> requests() {
        return List.of(
                ContestScoreboardApplier.ApplyRequest.rebuild(3L, update(3L)),
                ContestScoreboardApplier.ApplyRequest.rebuild(5L, update(5L))
        );
    }

    private static ContestScoreboardUpdate update(long submissionId) {
        return new ContestScoreboardUpdate(submissionId, 77L, 201L, 100L, null, null, null, null);
    }
}
