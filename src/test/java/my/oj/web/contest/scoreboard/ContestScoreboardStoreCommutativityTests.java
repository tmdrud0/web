package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.memory.InMemoryContestScoreboardStore;
import my.oj.web.contest.scoreboard.redis.FakeContestRedisKeyValueClient;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardStore;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract both {@link ContestScoreboardStore} implementations have to honour: applying a
 * set of judgements must reach the same scoreboard no matter in which order — or how often —
 * each judgement arrives. Judging latency varies per submission, so the live scoreboard applies
 * judgements in an order that differs from the submission order, while a rebuild replays them
 * in submission order. Both have to land on the same numbers.
 *
 * <p>The Lua implementation of the same rule is covered by
 * {@code RedisContestScoreboardOutboxApplierRedisIntegrationTests}.
 */
class ContestScoreboardStoreCommutativityTests {

    private static final long CONTEST_ID = 4242L;
    private static final long USER_ID = 7L;
    private static final long PROBLEM_ID = 11L;
    private static final LocalDateTime CONTEST_START = LocalDateTime.of(2026, 3, 10, 10, 0);

    static Stream<Named> stores() {
        return Stream.of(
                new Named("memory", InMemoryContestScoreboardStore::new),
                new Named("redis", () -> new RedisContestScoreboardStore(new FakeContestRedisKeyValueClient()))
        );
    }

    /**
     * The failure this whole design exists for: a WRONG submitted at minute 10 took two seconds
     * to judge while an ACCEPTED submitted at minute 12 took ten milliseconds, so the ACCEPTED
     * lands first. The wrong attempt still has to cost its five penalty minutes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void lateWrongAttemptStillCostsPenaltyWhenTheAcceptedArrivedFirst(Named named) {
        ContestScoreboardStore store = named.create();

        record(store, 1L, event(1_001L, 12, SubmissionResult.ACCEPTED));
        record(store, 2L, event(1_002L, 10, SubmissionResult.WRONG_ANSWER));

        assertThat(store.currentRanking(CONTEST_ID))
                .containsExactly(new ContestScoreboardEntry(USER_ID, 1, 17L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void arrivalOrderDoesNotChangeTheFinalScoreboard(Named named) {
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

        List<ContestScoreboardEntry> expected = applyAll(named, events);

        Random random = new Random(20260730L);
        for (int permutation = 0; permutation < 25; permutation++) {
            List<Event> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, random);

            assertThat(applyAll(named, shuffled))
                    .as("permutation %d: %s", permutation, submissionIdsOf(shuffled))
                    .isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void liveApplyOrderMatchesAReplayInSubmissionOrder(Named named) {
        List<Event> submissionOrder = List.of(
                event(3_001L, 4, SubmissionResult.WRONG_ANSWER),
                event(3_002L, 9, SubmissionResult.WRONG_ANSWER),
                event(3_003L, 11, SubmissionResult.ACCEPTED),
                event(3_004L, 15, 12L, SubmissionResult.WRONG_ANSWER),
                event(3_005L, 15, 12L, SubmissionResult.ACCEPTED),
                event(3_006L, 21, 12L, SubmissionResult.WRONG_ANSWER)
        );
        // Judging is slower for some submissions, so the live scoreboard sees another order.
        List<Event> judgedOrder = List.of(
                submissionOrder.get(2),
                submissionOrder.get(4),
                submissionOrder.get(0),
                submissionOrder.get(5),
                submissionOrder.get(3),
                submissionOrder.get(1)
        );

        assertThat(applyAll(named, judgedOrder)).isEqualTo(applyAll(named, submissionOrder));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void redeliveringTheSameEventLeavesTheScoreUnchanged(Named named) {
        ContestScoreboardStore store = named.create();
        Event wrong = event(4_001L, 6, SubmissionResult.WRONG_ANSWER);
        Event accepted = event(4_002L, 18, SubmissionResult.ACCEPTED);

        record(store, 41L, wrong);
        record(store, 42L, accepted);
        List<ContestScoreboardEntry> afterFirstDelivery = store.currentRanking(CONTEST_ID);

        record(store, 41L, wrong);
        record(store, 42L, accepted);

        assertThat(afterFirstDelivery).containsExactly(new ContestScoreboardEntry(USER_ID, 1, 23L));
        assertThat(store.currentRanking(CONTEST_ID)).isEqualTo(afterFirstDelivery);
    }

    /** Re-applying the same attempt under a fresh event ID must not double count it either. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void reapplyingAnAttemptUnderANewEventIdLeavesTheScoreUnchanged(Named named) {
        ContestScoreboardStore store = named.create();
        Event wrong = event(5_001L, 6, SubmissionResult.WRONG_ANSWER);
        Event accepted = event(5_002L, 18, SubmissionResult.ACCEPTED);

        record(store, 51L, wrong);
        record(store, 52L, accepted);

        record(store, 53L, accepted);
        record(store, 54L, wrong);

        assertThat(store.currentRanking(CONTEST_ID))
                .containsExactly(new ContestScoreboardEntry(USER_ID, 1, 23L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void pendingEventScoresNothing(Named named) {
        ContestScoreboardStore store = named.create();

        record(store, 61L, event(6_001L, 5, SubmissionResult.PENDING));

        assertThat(store.currentRanking(CONTEST_ID)).isEmpty();
        assertThat(store.totalParticipants(CONTEST_ID)).isZero();

        record(store, 62L, event(6_002L, 7, SubmissionResult.WRONG_ANSWER));
        record(store, 63L, event(6_003L, 9, SubmissionResult.PENDING));
        record(store, 64L, event(6_004L, 11, SubmissionResult.ACCEPTED));

        assertThat(store.currentRanking(CONTEST_ID))
                .containsExactly(new ContestScoreboardEntry(USER_ID, 1, 16L));
    }

    private static List<ContestScoreboardEntry> applyAll(Named named, List<Event> events) {
        ContestScoreboardStore store = named.create();
        long eventId = 1L;
        for (Event event : events) {
            record(store, eventId++, event);
        }
        return store.currentRanking(CONTEST_ID);
    }

    private static void record(ContestScoreboardStore store, long eventId, Event event) {
        store.recordJudgement(
                eventId,
                event.contestSubmissionId(),
                CONTEST_ID,
                event.problemId(),
                event.userId(),
                CONTEST_START,
                CONTEST_START.plusMinutes(event.submittedMinute()),
                event.result()
        );
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

    private record Named(String name, Supplier<ContestScoreboardStore> factory) {

        ContestScoreboardStore create() {
            return factory.get();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
