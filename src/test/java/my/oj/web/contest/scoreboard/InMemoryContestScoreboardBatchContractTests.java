package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyRequest;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyResult;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier.ApplyStatus;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboard;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardApplier;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The perf / rank-bench store answers a batch with the same contract as the Redis script. */
class InMemoryContestScoreboardBatchContractTests {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 10, 0);

    @Test
    void appliedDuplicateAndSequenceAreReportedPerEvent() {
        InMemoryContestScoreboardApplier applier =
                new InMemoryContestScoreboardApplier(new InMemoryContestScoreboard(), () -> true);

        List<ApplyResult> results = applier.applyAll(List.of(
                ApplyRequest.stream(1L, update(11L, SubmissionResult.ACCEPTED), CheckpointAdvance.ANCHOR),
                ApplyRequest.stream(1L, update(11L, SubmissionResult.ACCEPTED)),
                ApplyRequest.stream(2L, update(11L, SubmissionResult.ACCEPTED)),
                ApplyRequest.stream(3L, update(12L, SubmissionResult.WRONG_ANSWER))
        ), ContestScoreboardApplier.NO_CHECKPOINT_FLOOR);

        assertThat(results).extracting(ApplyResult::status).containsExactly(
                ApplyStatus.APPLIED, ApplyStatus.DUPLICATE, ApplyStatus.DUPLICATE, ApplyStatus.APPLIED);
        assertThat(results).extracting(ApplyResult::appliedOffset).containsExactly(1L, 1L, 2L, 3L);
        assertThat(results).extracting(ApplyResult::sequence).containsExactly(1L, null, null, 2L);
    }

    @Test
    void aStoredOffsetBelowTheFloorRefusesTheBatchWithoutApplyingIt() {
        InMemoryContestScoreboardApplier applier = new InMemoryContestScoreboardApplier(new InMemoryContestScoreboard());
        applier.applyAll(List.of(ApplyRequest.stream(9L, update(11L, SubmissionResult.ACCEPTED), CheckpointAdvance.ANCHOR)));

        List<ApplyResult> results = applier.applyAll(List.of(
                ApplyRequest.stream(20L, update(12L, SubmissionResult.ACCEPTED))), 19L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.rolledBack()).isTrue();
            assertThat(result.appliedOffset()).isEqualTo(9L);
        });
        assertThat(applier.currentStreamOffset()).isEqualTo(9L);
        assertThat(applier.applyAll(List.of(ApplyRequest.stream(20L, update(12L, SubmissionResult.ACCEPTED))), 9L))
                .singleElement().satisfies(result -> assertThat(result.newlyApplied()).isTrue());
    }

    @Test
    void aFailureStopsTheBatch() {
        InMemoryContestScoreboardApplier applier = new InMemoryContestScoreboardApplier(new InMemoryContestScoreboard());
        ContestScoreboardUpdate missingUser = new ContestScoreboardUpdate(13L, 1L, 2L, null, START,
                START.plusMinutes(3), SubmissionResult.ACCEPTED, START.plusMinutes(4));

        List<ApplyResult> results = applier.applyAll(List.of(
                ApplyRequest.stream(1L, update(11L, SubmissionResult.ACCEPTED), CheckpointAdvance.ANCHOR),
                ApplyRequest.stream(2L, missingUser),
                ApplyRequest.stream(3L, update(12L, SubmissionResult.ACCEPTED))
        ));

        assertThat(results).extracting(ApplyResult::status).containsExactly(ApplyStatus.APPLIED, ApplyStatus.FAILED);
        assertThat(applier.currentStreamOffset()).isEqualTo(1L);
    }

    private static ContestScoreboardUpdate update(long submissionId, SubmissionResult result) {
        return new ContestScoreboardUpdate(submissionId, 1L, 2L, 3L, START, START.plusMinutes(submissionId),
                result, START.plusMinutes(submissionId + 1));
    }
}
