package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.testsupport.NoOpTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The apply-and-record pair the two replaying recovery modes share.
 *
 * <p>Two things are pinned here, and they pull in opposite directions on purpose. A chunk the
 * scoreboard refused must fail the chunk, because a marker written over a refused chunk would leave
 * MySQL claiming results the scoreboard never took. A marker that could not be written must
 * <em>not</em> fail the chunk, because the scoreboard write is already durable and cannot be taken
 * back - the mismatch is repaired by the next pass re-offering the same results, and abandoning the
 * rest of the batch over a missing timestamp would repair nothing.</p>
 *
 * <p>The retry is real here rather than a stub: a mocked executor would run the marker once and the
 * test would call that a retry. What the real one is doing - three attempts on the marker with its
 * own bounds, and no second visit to the scoreboard - is the whole of the claim.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardReplayApplicationTests {

    @Mock
    private ContestScoreboardApplier scoreboardApplier;
    @Mock
    private ContestScoreboardAppliedMarker appliedMarker;

    private SimpleMeterRegistry registry;
    private ContestScoreboardReplayApplication application;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ContestSubmissionBatchExecutor batchExecutor =
                new ContestSubmissionBatchExecutor(new NoOpTransactionManager());
        application = new ContestScoreboardReplayApplication(
                scoreboardApplier,
                appliedMarker,
                new ContestScoreboardApplyLock(),
                batchExecutor,
                registry
        );
    }

    @Test
    void failsTheChunkWhenTheScoreboardRefusedAResult() {
        when(scoreboardApplier.applyAll(anyList())).thenReturn(List.of(
                ContestScoreboardApplier.ApplyResult.failure(3L, "wrong Redis key type")));

        assertThatThrownBy(() -> apply(requests()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong Redis key type");

        // The refused result is the only one there is, so there is nothing to record - and recording
        // the others would be worse than recording none, because the marker is what a later pass
        // reads to decide a result is done.
        verify(appliedMarker, never()).markApplied(anyList());
    }

    @Test
    void failsTheChunkWhenTheBatchStoppedShort() {
        when(scoreboardApplier.applyAll(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> apply(requests()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch stopped before every result was applied")
                .hasMessageContaining("sequenced results");

        verify(appliedMarker, never()).markApplied(anyList());
    }

    /**
     * A marker that failed needs the database, not another {@code EVAL} under the apply lock. The
     * scoreboard is asked once and the marker is retried on its own until it takes.
     */
    @Test
    void retriesAMarkerThatFailedTransientlyWithoutTouchingTheScoreboardAgain() {
        givenTheScoreboardAccepts();
        doThrow(new IllegalStateException("deadlock"))
                .doThrow(new IllegalStateException("deadlock"))
                .doNothing()
                .when(appliedMarker).markApplied(anyList());

        application.apply(requests(), "sequenced results");

        verify(appliedMarker, times(3)).markApplied(List.of(3L, 5L));
        verify(scoreboardApplier, times(1)).applyAll(anyList());
        assertThat(markerFailures()).isZero();
    }

    /**
     * The last resort, and the one that must not throw: the results are on the scoreboard and the
     * caller's batch has to finish. What is left is to make the missing marker visible, because its
     * consequence - results that look unapplied until the next pass - is otherwise silent.
     */
    @Test
    void countsAMarkerThatKeptFailingAndLeavesTheScoreboardAsItIs() {
        givenTheScoreboardAccepts();
        doThrow(new IllegalStateException("deadlock")).when(appliedMarker).markApplied(anyList());

        assertThatCode(() -> application.apply(requests(), "sequenced results"))
                .doesNotThrowAnyException();

        assertThat(markerFailures()).isEqualTo(1.0);
        verify(appliedMarker, times(3)).markApplied(List.of(3L, 5L));
        // A failure to record is not a failure to apply: the scoreboard keeps what it was given.
        verify(scoreboardApplier, times(1)).applyAll(anyList());
    }

    private void apply(List<ContestScoreboardApplier.ApplyRequest> requests) {
        application.apply(requests, "sequenced results");
    }

    private void givenTheScoreboardAccepts() {
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> {
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), null))
                    .toList();
        });
    }

    private double markerFailures() {
        return registry.get("contest.scoreboard.recovery.marker.failed").counter().count();
    }

    private static List<ContestScoreboardApplier.ApplyRequest> requests() {
        return List.of(
                ContestScoreboardApplier.ApplyRequest.rebuild(3L, update(3L)),
                ContestScoreboardApplier.ApplyRequest.rebuild(5L, update(5L))
        );
    }

    private static ContestScoreboardUpdate update(long submissionId) {
        return new ContestScoreboardUpdate(
                submissionId, 77L, 201L, 100L, null, null, null, null);
    }
}
