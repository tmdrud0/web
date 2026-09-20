package my.oj.web.contest.scoreboard.recovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
     * A registration that lands while the release is running still runs, and runs once.
     *
     * <p>The waiting list is taken and cleared under the same lock the registration checks, so this is
     * the interleaving the lock exists for: without it, an action could check "not covered" a moment
     * before coverage was recorded and be added to a list nobody would look at again.</p>
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
