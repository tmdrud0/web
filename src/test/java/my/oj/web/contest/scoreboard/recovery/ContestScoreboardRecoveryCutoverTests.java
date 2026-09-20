package my.oj.web.contest.scoreboard.recovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The boundary a mode's own history recovery has to cross before its consumer may read anything.
 *
 * <p>What is pinned is that nothing registered before the boundary is lost and nothing runs twice. Both
 * mistakes mean the same thing here: an action lost is a consumer that never starts, and an action run
 * twice is a listener container started twice - and the two only meet in one place, which is the
 * instant coverage is recorded while something is registering.</p>
 */
class ContestScoreboardRecoveryCutoverTests {

    private final ContestScoreboardRecoveryCutover cutover = new ContestScoreboardRecoveryCutover();

    @Test
    void holdsAnActionRegisteredBeforeTheHistoryIsCovered() {
        List<String> ran = new ArrayList<>();

        cutover.whenCovered(() -> ran.add("consumer"));

        assertThat(cutover.isCovered()).isFalse();
        assertThat(ran).as("nothing may run before the mode's own recovery has").isEmpty();
    }

    @Test
    void runsAHeldActionWhenTheHistoryIsCovered() {
        List<String> ran = new ArrayList<>();
        cutover.whenCovered(() -> ran.add("consumer"));

        cutover.markCovered("the full-replay startup replay");

        assertThat(ran).containsExactly("consumer");
        assertThat(cutover.isCovered()).isTrue();
    }

    /**
     * The other order, which is the one a restart inside a live context produces: the recovery has
     * already reported and a consumer is brought up afterwards. It must not be held for a boundary that
     * has already been crossed.
     */
    @Test
    void runsAnActionRegisteredAfterTheHistoryIsCovered() {
        List<String> ran = new ArrayList<>();
        cutover.markCovered("the redis-seq startup check");

        cutover.whenCovered(() -> ran.add("consumer"));

        assertThat(ran).containsExactly("consumer");
    }

    /**
     * Reporting twice must not run what is waiting twice. The passes are independent triggers - a
     * startup check and two periodic ones - so a second report is ordinary, and a second start of a
     * listener container is not.
     */
    @Test
    void onlyTheFirstReportReleasesWhatIsWaiting() {
        List<String> ran = new ArrayList<>();
        cutover.whenCovered(() -> ran.add("consumer"));

        cutover.markCovered("the redis-seq startup check");
        cutover.markCovered("the redis-seq duplicate-check check");

        assertThat(ran).containsExactly("consumer");
    }

    /**
     * The defect this pins: a deferred action that failed was gone.
     *
     * <p>Recording coverage before running what was waiting is right - the history <em>is</em> covered -
     * but the list it ran from was cleared in the same breath, so an action that threw was neither
     * waiting nor run. The consumer it starts stayed down for the life of the JVM: every later report of
     * the boundary returned early on the coverage flag that was already set, and the redis-seq periodic
     * check is exactly such a later report.</p>
     *
     * <p>The exception itself is not the defect and must not be swallowed - it is what tells the pass
     * that the thing it released could not start. What must survive it is the action.</p>
     */
    @Test
    void aDeferredActionThatFailedIsRetriedWhenTheBoundaryIsReportedAgain() {
        AtomicInteger attempts = new AtomicInteger();
        cutover.whenCovered(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("the broker is not reachable yet");
            }
        });

        assertThatThrownBy(() -> cutover.markCovered("the redis-seq startup check"))
                .as("a release that could not start anything is not swallowed")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the broker is not reachable yet");

        assertThat(cutover.isCovered()).isTrue();
        assertThat(cutover.awaiting())
                .as("the action that failed is still waiting on the boundary, not gone with the report")
                .isEqualTo(1);

        assertThatCode(() -> cutover.markCovered("the redis-seq duplicate-check check"))
                .doesNotThrowAnyException();
        assertThat(attempts)
                .as("the action was still waiting on the boundary, so the next report ran it")
                .hasValue(2);
        assertThat(cutover.awaiting()).isZero();
    }

    /**
     * A report that arrives after the action already succeeded must not run it again: the passes are
     * independent triggers, and a second start of a listener container is the mistake the whole boundary
     * exists to prevent.
     */
    @Test
    void aDeferredActionThatSucceededIsNotRunAgainByALaterReport() {
        AtomicInteger runs = new AtomicInteger();
        cutover.whenCovered(runs::incrementAndGet);

        cutover.markCovered("the full-replay startup replay");
        cutover.markCovered("the redis-seq duplicate-check check");
        cutover.markCovered("the redis-seq lost-tail-check check");

        assertThat(runs).hasValue(1);
        assertThat(cutover.awaiting())
                .as("success is what empties the list, so there is nothing left to run twice")
                .isZero();
    }

    /**
     * The boundary is not held while a deferred action runs.
     *
     * <p>The action starts a listener container, and the container talks to the broker while the passes
     * that report the boundary are still reporting it. Running the action under the monitor would put
     * those two on each other's critical path, and this is the guarantee the release is written the way
     * it is for - a monitor held across the callback would block the registration below until the
     * callback returned.</p>
     */
    @Test
    void theBoundaryIsNotHeldWhileADeferredActionRuns() throws Exception {
        CountDownLatch actionRunning = new CountDownLatch(1);
        CountDownLatch letItFinish = new CountDownLatch(1);
        cutover.whenCovered(() -> {
            actionRunning.countDown();
            try {
                letItFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        Thread releasing = new Thread(() -> cutover.markCovered("the mode's startup pass"));
        releasing.start();
        ExecutorService prober = Executors.newSingleThreadExecutor();
        try {
            assertThat(actionRunning.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> covered = prober.submit(cutover::isCovered);
            assertThat(covered.get(5, TimeUnit.SECONDS))
                    .as("the boundary is reachable while the action it released is still running")
                    .isTrue();
        } finally {
            prober.shutdownNow();
            letItFinish.countDown();
            releasing.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    /**
     * Two reports cannot serve the same action twice.
     *
     * <p>The reports are independent triggers - {@code redis-seq} has three, two of them on the
     * scheduler thread - and a report that is still inside {@code container.start()} is slow enough
     * (an unreachable broker means a connect timeout) for the next one to arrive while it runs. What
     * the second must not do is start the same listener container again: the action is taken off the
     * waiting list by the report that runs it, in the same lock that takes the list.</p>
     */
    @Test
    void aSecondReportDoesNotRunWhatTheFirstIsAlreadyRunning() throws Exception {
        CountDownLatch actionRunning = new CountDownLatch(1);
        CountDownLatch letItFinish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        cutover.whenCovered(() -> {
            runs.incrementAndGet();
            actionRunning.countDown();
            try {
                letItFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        Thread first = new Thread(() -> cutover.markCovered("the redis-seq startup check"));
        first.start();
        try {
            assertThat(actionRunning.await(5, TimeUnit.SECONDS)).isTrue();

            // The boundary is covered and its action is running: this report has nothing left to do.
            cutover.markCovered("the redis-seq duplicate-check check");

            assertThat(runs)
                    .as("one start of one listener container, however many reports arrive")
                    .hasValue(1);
        } finally {
            letItFinish.countDown();
            first.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    /**
     * One action failing must not take the others with it. The waiters are independent - the boundary
     * holds one deferred start per consumer - and a release that dropped the rest because the first one
     * threw would lose exactly what this class promises to keep.
     */
    @Test
    void aFailedActionDoesNotKeepAnotherWaiting() {
        AtomicInteger failures = new AtomicInteger();
        List<String> ran = new ArrayList<>();
        cutover.whenCovered(() -> ran.add("first"));
        cutover.whenCovered(() -> {
            failures.incrementAndGet();
            throw new IllegalStateException("the first consumer could not start");
        });
        cutover.whenCovered(() -> ran.add("third"));

        assertThatThrownBy(() -> cutover.markCovered("the mode's startup pass"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ran).containsExactly("first", "third");
        assertThat(failures).hasValue(1);
    }

    /**
     * A registration that lands while the release is running still runs, and runs once.
     *
     * <p>The "is the boundary already crossed" decision and the registration are taken under the same
     * lock the report takes the waiting list under, so this is the interleaving the lock exists for:
     * without it, an action could check "not covered" a moment before coverage was recorded and be added
     * to a list nobody would look at again.</p>
     */
    @Test
    void aRegistrationRacingTheReleaseIsNeitherLostNorRunTwice() throws Exception {
        int registrations = 64;
        CountDownLatch started = new CountDownLatch(1);
        List<String> ran = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < registrations; index++) {
            threads.add(new Thread(() -> {
                try {
                    started.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                cutover.whenCovered(() -> ran.add("consumer"));
            }));
        }
        threads.forEach(Thread::start);
        started.countDown();
        cutover.markCovered("the full-replay startup replay");
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(ran)
                .as("every registration runs exactly once, whether it lost the race or not")
                .hasSize(registrations);
    }
}
