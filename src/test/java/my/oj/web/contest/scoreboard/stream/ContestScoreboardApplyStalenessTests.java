package my.oj.web.contest.scoreboard.stream;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyResult;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy;
import my.oj.web.contest.submission.messaging.ContestJudgeResultStreamMessage;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code contest.scoreboard.apply.staleness}: recorded for results newly applied, and only for those. */
class ContestScoreboardApplyStalenessTests {

    private ContestScoreboardApplier applier;
    private ContestScoreboardStreamPosition position;
    private SimpleMeterRegistry registry;
    private ContestScoreboardStreamProcessor processor;

    @BeforeEach
    void setUp() {
        applier = mock(ContestScoreboardApplier.class);
        ContestScoreboardRecoveryStrategy strategy = mock(ContestScoreboardRecoveryStrategy.class);
        lenient().when(strategy.mode()).thenReturn(ContestScoreboardRecoveryMode.STREAM_OFFSET);
        position = new ContestScoreboardStreamPosition();
        registry = new SimpleMeterRegistry();
        processor = new ContestScoreboardStreamProcessor(applier, mock(ContestScoreboardAppliedAtCompletion.class),
                position, strategy, new ContestScoreboardStreamMetrics(registry), new ContestScoreboardApplyLock());
    }

    @Test
    void onlyAppliedResultsAreRecordedAndDuplicatesAreNot() {
        when(applier.currentStreamOffset()).thenReturn(4L, 4L, 7L);
        when(applier.applyAll(anyList(), anyLong())).thenReturn(List.of(
                ApplyResult.duplicate(4L, 4L),
                ApplyResult.applied(5L, 5L, null),
                ApplyResult.duplicate(6L, 6L),
                ApplyResult.applied(7L, 7L, null)));
        LocalDateTime judgedAt = LocalDateTime.now().minusSeconds(3);

        processor.process(List.of(event(4L, judgedAt), event(5L, judgedAt), event(6L, judgedAt), event(7L, judgedAt)));

        assertThat(staleness(false).count()).isEqualTo(2L);
        assertThat(staleness(true).count()).isZero();
        assertThat(staleness(false).max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(3_000d);
    }

    /**
     * After a rollback the resubscribe brings the lost range back: those results are applied a second
     * time, at or below what this JVM had already applied, and are told apart from first applications.
     */
    @Test
    void theLostRangeComingBackAfterARollbackIsTaggedReplayed() {
        position.recordAppliedOffset(19L);
        position.consumerRestarted();
        when(applier.currentStreamOffset()).thenReturn(9L, 9L, 21L);
        when(applier.applyAll(anyList(), anyLong())).thenReturn(List.of(
                ApplyResult.duplicate(9L, 9L),
                ApplyResult.applied(10L, 10L, null),
                ApplyResult.applied(19L, 19L, null),
                ApplyResult.applied(21L, 21L, null)));
        LocalDateTime judgedAt = LocalDateTime.now().minusSeconds(1);

        processor.process(List.of(event(9L, judgedAt), event(10L, judgedAt), event(19L, judgedAt), event(21L, judgedAt)));

        assertThat(staleness(true).count()).isEqualTo(2L);
        assertThat(staleness(false).count()).isEqualTo(1L);
    }

    @Test
    void whatABatchAppliedBeforeItFailedIsStillRecordedOnce() {
        when(applier.currentStreamOffset()).thenReturn(4L, 4L);
        when(applier.applyAll(anyList(), anyLong())).thenReturn(List.of(
                ApplyResult.applied(5L, 5L, null),
                ApplyResult.failure(6L, "poison")));
        LocalDateTime judgedAt = LocalDateTime.now();

        assertThatThrownBy(() -> processor.process(List.of(event(4L, judgedAt), event(5L, judgedAt), event(6L, judgedAt))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(staleness(false).count()).isEqualTo(1L);
    }

    private Timer staleness(boolean replayed) {
        return registry.get("contest.scoreboard.apply.staleness").tag("replayed", Boolean.toString(replayed)).timer();
    }

    private static ContestScoreboardStreamEvent event(long offset, LocalDateTime judgedAt) {
        return new ContestScoreboardStreamEvent(offset, new ContestJudgeResultStreamMessage(
                ContestJudgeResultStreamMessage.CURRENT_SCHEMA_VERSION, 100L + offset, 10L, 20L, 30L,
                judgedAt.minusHours(1), judgedAt.minusMinutes(1), judgedAt, SubmissionResult.ACCEPTED));
    }
}
