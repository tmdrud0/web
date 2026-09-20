package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboard;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardApplier;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory applier is the test seam for the sequence contract, so it has to answer the same
 * questions the Redis script does - otherwise a MySQL-backed test of the detection paths would be
 * driving a scoreboard that does not behave like the real one.
 */
class InMemoryContestScoreboardSequenceTests {

    private static final long CONTEST_ID = 77L;
    private static final long USER_ID = 5L;
    private static final long PROBLEM_ID = 3L;

    @Test
    void withoutSequenceTrackingNothingIsSequenced() {
        InMemoryContestScoreboardApplier applier = applier(ContestScoreboardSequenceTracking.DISABLED);

        applier.apply(stream(0L, 1001L));
        applier.apply(stream(1L, 1002L));

        assertThat(applier.appliedSequences(List.of(1001L, 1002L))).isEmpty();
    }

    @Test
    void sequencesAreGlobalAndStrictlyIncreasing() {
        InMemoryContestScoreboardApplier applier = applier(() -> true);

        applier.apply(stream(0L, 1001L));
        applier.apply(stream(1L, 1002L));

        assertThat(applier.appliedSequences(List.of(1001L, 1002L)))
                .containsExactly(Map.entry(1001L, 1L), Map.entry(1002L, 2L));
    }

    @Test
    void aDuplicateDeliveryKeepsTheSequenceTheScoreboardAlreadyHas() {
        InMemoryContestScoreboardApplier applier = applier(() -> true);

        applier.apply(stream(0L, 1001L));
        applier.apply(stream(1L, 1001L));

        assertThat(applier.appliedSequences(List.of(1001L))).containsExactly(Map.entry(1001L, 1L));
        // The next genuinely new result must not skip a value, which is what makes the allocator
        // readable as "how many sequences have been issued".
        applier.apply(stream(2L, 1002L));
        assertThat(applier.appliedSequences(List.of(1002L))).containsExactly(Map.entry(1002L, 2L));
    }

    /**
     * Re-applying a submission the scoreboard forgot - a reset dropped its processed marker, which
     * is what a contest rebuild does - is a new application and gets a strictly later sequence.
     */
    @Test
    void reApplyingAfterAResetGetsAFreshLaterSequence() {
        InMemoryContestScoreboardApplier applier = applier(() -> true);
        applier.apply(stream(0L, 1001L));

        applier.reset(CONTEST_ID);
        applier.apply(stream(1L, 1001L));

        assertThat(applier.appliedSequences(List.of(1001L)).get(1001L)).isEqualTo(2L);
    }

    @Test
    void anUnjudgedResultIsNotSequenced() {
        InMemoryContestScoreboardApplier applier = applier(() -> true);

        applier.apply(ContestScoreboardApplier.ApplyRequest.rebuild(
                1010L, payload(1010L, SubmissionResult.PENDING)));

        assertThat(applier.appliedSequences(List.of(1010L))).isEmpty();
    }

    private InMemoryContestScoreboardApplier applier(ContestScoreboardSequenceTracking tracking) {
        return new InMemoryContestScoreboardApplier(new InMemoryContestScoreboard(), tracking);
    }

    private ContestScoreboardApplier.ApplyRequest stream(long offset, long submissionId) {
        return ContestScoreboardApplier.ApplyRequest.stream(
                offset, payload(submissionId, SubmissionResult.ACCEPTED));
    }

    private ContestScoreboardUpdate payload(long submissionId, SubmissionResult result) {
        return new ContestScoreboardUpdate(
                submissionId,
                CONTEST_ID,
                PROBLEM_ID,
                USER_ID,
                LocalDateTime.of(2026, 3, 10, 10, 0),
                LocalDateTime.of(2026, 3, 10, 10, 5),
                result,
                null
        );
    }
}
