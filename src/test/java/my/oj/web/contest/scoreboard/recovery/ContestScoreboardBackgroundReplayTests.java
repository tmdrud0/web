package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.LostRange;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.Outcome;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * full-replay's rollback replay on its own thread: what a request promises, how requests coalesce, and
 * that the thread that asked is never the one that replays.
 */
class ContestScoreboardBackgroundReplayTests {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final ContestScoreboardFullReplayService replayService = mock(ContestScoreboardFullReplayService.class);
    private final ContestScoreboardRecoveryPassGate gate = new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry());
    private final List<ContestScoreboardBackgroundReplay> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(ContestScoreboardBackgroundReplay::close);
    }

    @Test
    void theStrategyAnswersCoveredAtOnceAndTheReplayRunsOnAnotherThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> replayThreads = new ArrayList<>();
        when(replayService.replayContestsNewestFirst(any())).thenAnswer(invocation -> {
            replayThreads.add(Thread.currentThread().getName());
            release.await(10, TimeUnit.SECONDS);
            return 1;
        });
        ContestScoreboardTouchedContests touched = new ContestScoreboardTouchedContests();
        touched.touched(7L, 30L);
        ContestScoreboardBackgroundReplay background = background();
        FullReplayRecoveryStrategy strategy = new FullReplayRecoveryStrategy(replayService, gate, touched, background);

        // The replay is still blocked, and the caller already has its answer.
        assertThat(strategy.rebuildHistory(new LostRange(20L, 40L, 40L, -1L))).isEqualTo(Outcome.COVERED);
        release.countDown();

        assertThat(background.awaitIdle(WAIT)).isTrue();
        assertThat(replayThreads).containsExactly("scoreboard-full-replay");
    }

    /**
     * Inside what this JVM applied, only the contests written at or above the restored checkpoint are
     * replayed; outside it, any contest may be involved and all of them are.
     */
    @Test
    void theScopeIsTheTouchedContestsInsideAppliedHistoryAndEveryContestOutsideIt() throws Exception {
        ContestScoreboardTouchedContests touched = new ContestScoreboardTouchedContests();
        touched.touched(1L, 10L);
        touched.touched(2L, 25L);
        touched.touched(3L, 40L);
        ContestScoreboardBackgroundReplay background = background();
        FullReplayRecoveryStrategy strategy = new FullReplayRecoveryStrategy(replayService, gate, touched, background);

        strategy.rebuildHistory(new LostRange(20L, 40L, 40L, -1L));
        assertThat(background.awaitIdle(WAIT)).isTrue();
        verify(replayService).replayContestsNewestFirst(Set.of(2L, 3L));

        strategy.rebuildHistory(new LostRange(40L, 55L, 45L, -1L));
        assertThat(background.awaitIdle(WAIT)).isTrue();
        verify(replayService).replayAllContestsNewestFirst();
    }

    /**
     * Questions that wait for the same pass are answered by it once. The worker is kept busy with an
     * earlier pass, so both questions arrive before the next pass starts.
     */
    @Test
    void questionsThatWaitForTheSamePassAreAnsweredByItOnce() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        when(replayService.replayContestsNewestFirst(any())).thenAnswer(invocation -> {
            passes.incrementAndGet();
            firstStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        });
        ContestScoreboardBackgroundReplay background = background();

        background.request(10L, 20L, Set.of(1L));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(background.request(20L, 40L, Set.of(7L))).isTrue();
        assertThat(background.request(20L, 40L, Set.of(7L))).isTrue();
        release.countDown();

        assertThat(background.awaitIdle(WAIT)).isTrue();
        assertThat(passes).hasValue(2);
    }

    /**
     * The hole this pins: a rollback, its pass under way newest first, and Redis rolled back again with
     * nothing applied in between. The second rollback is named exactly like the first, and the running
     * pass may already have walked past what it took away - so it gets a pass of its own.
     */
    @Test
    void aRepeatedRollbackDuringThePassItRepeatsGetsAnotherPass() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        when(replayService.replayContestsNewestFirst(any())).thenAnswer(invocation -> {
            passes.incrementAndGet();
            firstStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        });
        ContestScoreboardBackgroundReplay background = background();

        background.request(20L, 40L, Set.of(7L));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(background.request(20L, 40L, Set.of(7L))).isTrue();
        release.countDown();

        assertThat(background.awaitIdle(WAIT)).isTrue();
        assertThat(passes).hasValue(2);
        assertThat(background.completedPasses()).isEqualTo(2L);
    }

    /**
     * The same scenario through the strategy, the way the lifecycle asks: after the first COVERED the
     * supervisor marks the range rebuilt through H, so the second rollback's range - same H, the same or
     * an older checkpoint - reads as rebuilt already. In the background that mark means "queued", and the
     * question still has to reach the replay.
     */
    @Test
    void aRangeMarkedRebuiltByAQueuedPassIsStillReplayedAgain() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        when(replayService.replayContestsNewestFirst(any())).thenAnswer(invocation -> {
            passes.incrementAndGet();
            firstStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        });
        ContestScoreboardTouchedContests touched = new ContestScoreboardTouchedContests();
        touched.touched(7L, 40L);
        ContestScoreboardBackgroundReplay background = background();
        FullReplayRecoveryStrategy strategy = new FullReplayRecoveryStrategy(replayService, gate, touched, background);

        assertThat(strategy.rebuildHistory(new LostRange(20L, 40L, 40L, -1L))).isEqualTo(Outcome.COVERED);
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        LostRange sameCheckpoint = new LostRange(20L, 40L, 40L, 40L);
        LostRange olderCheckpoint = new LostRange(15L, 40L, 40L, 40L);
        assertThat(sameCheckpoint.rebuiltAlready()).isTrue();
        assertThat(strategy.rebuildHistory(sameCheckpoint)).isEqualTo(Outcome.COVERED);
        assertThat(strategy.rebuildHistory(olderCheckpoint)).isEqualTo(Outcome.COVERED);
        release.countDown();

        assertThat(background.awaitIdle(WAIT)).isTrue();
        // The two repeated questions waited for the same next pass.
        assertThat(passes).hasValue(2);
    }

    /** A pass that finished is not an answer to a rollback that came after it, even one named the same. */
    @Test
    void theSameRollbackAfterItsPassFinishedIsReplayedAgain() throws Exception {
        ContestScoreboardBackgroundReplay background = background();

        background.request(20L, 40L, Set.of(7L));
        assertThat(background.awaitIdle(WAIT)).isTrue();
        background.request(20L, 40L, Set.of(7L));
        assertThat(background.awaitIdle(WAIT)).isTrue();

        verify(replayService, org.mockito.Mockito.times(2)).replayContestsNewestFirst(Set.of(7L));
        assertThat(background.completedPasses()).isEqualTo(2L);
    }

    /**
     * A second rollback that arrives while a pass runs is served by a pass that starts after it - the
     * running one may have read MySQL before the second rollback took its writes away.
     */
    @Test
    void aRollbackThatArrivesDuringAPassGetsAPassThatStartsAfterIt() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Collection<Long>> scopes = new ArrayList<>();
        when(replayService.replayContestsNewestFirst(any())).thenAnswer(invocation -> {
            scopes.add(Set.copyOf(invocation.getArgument(0)));
            firstStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        });
        ContestScoreboardBackgroundReplay background = background();

        background.request(20L, 40L, Set.of(1L));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
        background.request(30L, 50L, Set.of(2L));
        background.request(35L, 55L, Set.of(3L));
        release.countDown();

        assertThat(background.awaitIdle(WAIT)).isTrue();
        // The two that arrived during the first pass are merged into the next one.
        assertThat(scopes).containsExactly(Set.of(1L), Set.of(2L, 3L));
        assertThat(background.completedPasses()).isEqualTo(2L);
    }

    @Test
    void aFailedPassIsRetriedUntilOneCompletes() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        when(replayService.replayContestsNewestFirst(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("database blip");
            }
            return 1;
        });
        ContestScoreboardBackgroundReplay background = background();

        background.request(20L, 40L, Set.of(7L));

        assertThat(background.awaitIdle(WAIT)).isTrue();
        assertThat(attempts).hasValue(3);
        assertThat(background.completedPasses()).isEqualTo(1L);
    }

    /** Another pass holding the gate - the startup replay, say - is waited out, not reported as done. */
    @Test
    void aPassThatFindsTheGateHeldWaitsForIt() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
            holding.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }));
        other.start();
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        ContestScoreboardBackgroundReplay background = background();

        background.request(20L, 40L, Set.of(7L));
        Thread.sleep(50L);
        verify(replayService, never()).replayContestsNewestFirst(any());
        release.countDown();
        other.join();

        assertThat(background.awaitIdle(WAIT)).isTrue();
        verify(replayService).replayContestsNewestFirst(Set.of(7L));
    }

    @Test
    void aClosedReplayRefusesAndTheStrategyAsksAgain() {
        ContestScoreboardBackgroundReplay background = background();
        FullReplayRecoveryStrategy strategy = new FullReplayRecoveryStrategy(
                replayService, gate, new ContestScoreboardTouchedContests(), background);
        background.close();

        assertThat(background.request(20L, 40L, null)).isFalse();
        assertThat(strategy.rebuildHistory(new LostRange(20L, 40L, 40L, -1L))).isEqualTo(Outcome.RETRYABLE_FAILURE);
    }

    @Test
    void aContestIsTouchedAtOrAboveACheckpointByItsLatestStamp() {
        ContestScoreboardTouchedContests touched = new ContestScoreboardTouchedContests();
        touched.touched(1L, 50L);
        touched.touched(1L, 10L);
        touched.touched(2L, 19L);
        touched.touched(3L, 20L);

        assertThat(touched.touchedAtOrAbove(20L)).containsExactly(1L, 3L);
        assertThat(touched.touchedAtOrAbove(-1L)).containsExactly(1L, 2L, 3L);
        assertThat(touched.touchedAtOrAbove(51L)).isEmpty();
    }

    private ContestScoreboardBackgroundReplay background() {
        ContestScoreboardBackgroundReplay background =
                new ContestScoreboardBackgroundReplay(replayService, gate, Duration.ofMillis(10));
        opened.add(background);
        return background;
    }
}
