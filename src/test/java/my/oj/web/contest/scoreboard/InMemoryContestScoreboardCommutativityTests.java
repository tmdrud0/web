package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboard;
import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardApplier;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory side of the scoreboard contract. The Redis Lua implementation is covered by
 * {@code RedisContestScoreboardApplierRedisIntegrationTests}.
 */
class InMemoryContestScoreboardCommutativityTests {

    private static final long CONTEST_ID = 4242L;
    private static final long USER_ID = 7L;
    private static final long PROBLEM_ID = 11L;
    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 3, 10, 10, 0);

    @Test
    void lateWrongAttemptStillCostsPenaltyWhenTheAcceptedArrivedFirst() {
        Harness harness = new Harness();

        harness.apply(1L, event(1_001L, 12, SubmissionResult.ACCEPTED));
        harness.apply(2L, event(1_002L, 10, SubmissionResult.WRONG_ANSWER));

        assertThat(harness.ranking())
                .containsExactly(new ContestScoreboardEntry(USER_ID, 1, 17L));
    }

    @Test
    void arrivalOrderDoesNotChangeTheFinalScoreboard() {
        List<Event> events = List.of(
                event(2_001L, 3, SubmissionResult.WRONG_ANSWER),
                event(2_002L, 10, SubmissionResult.WRONG_ANSWER),
                event(2_003L, 12, SubmissionResult.ACCEPTED),
                event(2_004L, 14, SubmissionResult.WRONG_ANSWER),
                event(2_005L, 20, SubmissionResult.ACCEPTED),
                event(2_006L, 8, 12L, SubmissionResult.WRONG_ANSWER),
                event(2_007L, 25, 12L, SubmissionResult.ACCEPTED),
                event(2_008L, 30, 13L, SubmissionResult.RUNTIME_ERROR),
                event(2_009L, 5, 99L, USER_ID + 1, SubmissionResult.ACCEPTED),
                event(2_010L, 6, 99L, USER_ID + 1, SubmissionResult.WRONG_ANSWER)
        );

        List<ContestScoreboardEntry> expected = applyAll(events);
        Random random = new Random(20260730L);
        for (int permutation = 0; permutation < 25; permutation++) {
            List<Event> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, random);

            assertThat(applyAll(shuffled))
                    .as("permutation %d: %s", permutation, submissionIdsOf(shuffled))
                    .isEqualTo(expected);
        }
    }

    @Test
    void liveApplyOrderMatchesAReplayInSubmissionOrder() {
        List<Event> submissionOrder = List.of(
                event(3_001L, 4, SubmissionResult.WRONG_ANSWER),
                event(3_002L, 9, SubmissionResult.WRONG_ANSWER),
                event(3_003L, 11, SubmissionResult.ACCEPTED),
                event(3_004L, 15, 12L, SubmissionResult.WRONG_ANSWER),
                event(3_005L, 15, 12L, SubmissionResult.ACCEPTED),
                event(3_006L, 21, 12L, SubmissionResult.WRONG_ANSWER)
        );
        List<Event> judgedOrder = List.of(
                submissionOrder.get(2),
                submissionOrder.get(4),
                submissionOrder.get(0),
                submissionOrder.get(5),
                submissionOrder.get(3),
                submissionOrder.get(1)
        );

        assertThat(applyAll(judgedOrder)).isEqualTo(applyAll(submissionOrder));
    }

    @Test
    void reapplyingAnAttemptUnderANewEventIdLeavesTheScoreUnchanged() {
        Harness harness = new Harness();
        Event wrong = event(5_001L, 6, SubmissionResult.WRONG_ANSWER);
        Event accepted = event(5_002L, 18, SubmissionResult.ACCEPTED);

        harness.apply(51L, wrong);
        harness.apply(52L, accepted);
        harness.apply(53L, accepted);
        harness.apply(54L, wrong);

        assertThat(harness.ranking())
                .containsExactly(new ContestScoreboardEntry(USER_ID, 1, 23L));
    }

    @Test
    void pendingEventScoresNothing() {
        Harness harness = new Harness();

        harness.apply(61L, event(6_001L, 5, SubmissionResult.PENDING));

        assertThat(harness.ranking()).isEmpty();
        assertThat(harness.scoreboard.totalParticipants(CONTEST_ID)).isZero();

        harness.apply(62L, event(6_002L, 7, SubmissionResult.WRONG_ANSWER));
        harness.apply(63L, event(6_003L, 9, SubmissionResult.PENDING));
        harness.apply(64L, event(6_004L, 11, SubmissionResult.ACCEPTED));

        assertThat(harness.ranking())
                .containsExactly(new ContestScoreboardEntry(USER_ID, 1, 16L));
    }

    private static List<ContestScoreboardEntry> applyAll(List<Event> events) {
        Harness harness = new Harness();
        long eventId = 1L;
        for (Event event : events) {
            harness.apply(eventId++, event);
        }
        return harness.ranking();
    }

    private static List<Long> submissionIdsOf(List<Event> events) {
        return events.stream().map(Event::contestSubmissionId).toList();
    }

    private static Event event(long contestSubmissionId, int submittedMinute, SubmissionResult result) {
        return event(contestSubmissionId, submittedMinute, PROBLEM_ID, result);
    }

    private static Event event(long contestSubmissionId,
                               int submittedMinute,
                               long problemId,
                               SubmissionResult result) {
        return event(contestSubmissionId, submittedMinute, problemId, USER_ID, result);
    }

    private static Event event(long contestSubmissionId,
                               int submittedMinute,
                               long problemId,
                               long userId,
                               SubmissionResult result) {
        return new Event(contestSubmissionId, submittedMinute, problemId, userId, result);
    }

    private record Event(long contestSubmissionId,
                         int submittedMinute,
                         long problemId,
                         long userId,
                         SubmissionResult result) {
    }

    private static final class Harness {
        private final InMemoryContestScoreboard scoreboard = new InMemoryContestScoreboard();
        private final InMemoryContestScoreboardApplier applier =
                new InMemoryContestScoreboardApplier(scoreboard);

        private void apply(long eventId, Event event) {
            applier.apply(eventId, new ContestScoreboardUpdate(
                    event.contestSubmissionId(),
                    CONTEST_ID,
                    event.problemId(),
                    event.userId(),
                    CONTEST_START,
                    CONTEST_START.plusMinutes(event.submittedMinute()),
                    event.result(),
                    null
            ));
        }

        private List<ContestScoreboardEntry> ranking() {
            return scoreboard.currentRanking(CONTEST_ID);
        }
    }
}
