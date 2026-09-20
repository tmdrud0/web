package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The boundary between a mode's own history recovery and live consumption.
 *
 * <h2>Why the boundary has to be stated rather than left to startup order</h2>
 *
 * <p>Every mode consumes the stream; what a mode owns is the answer to how the history a restored
 * Redis is missing gets rebuilt. {@code stream-offset} answers it by re-reading the stream from the
 * stored checkpoint, so its consumer starting there <em>is</em> its recovery.
 * {@code full-replay} answers it by re-sending MySQL and {@code redis-seq} by checking the sequence,
 * and for those two the same consumer start is not a recovery at all - it is the live path, and one
 * that happens to run first.</p>
 *
 * <p>Nothing in the container makes that order deliberate. A stream consumer is a
 * {@code SmartLifecycle} and starts at the end of the context refresh; the startup passes are
 * {@code ApplicationRunner}s and run after it. So a JVM that came up in {@code full-replay} would
 * begin re-reading the stream before its replay had run, and the history the restored scoreboard was
 * missing would be put back by the stream - the basis of the mode it is not running. The three modes
 * would then be indistinguishable at exactly the moment they are supposed to differ, and no amount of
 * correct wiring after startup would tell them apart.</p>
 *
 * <p>This type is that order made explicit. A mode whose history recovery is a startup pass holds its
 * consumer here; the pass reports the history covered when it has run; the consumer starts then. The
 * order is a dependency rather than a coincidence of lifecycle phases.</p>
 *
 * <h2>What holding the consumer does not do</h2>
 *
 * <p>It does not move the checkpoint and it does not skip anything. The consumer is never started at a
 * broker tail or at a {@code next} position - every start in
 * {@code ContestScoreboardStreamLifecycle} asks for the stored checkpoint itself, inclusive - and the
 * startup passes write no stream offset at all, so the checkpoint the hold begins with is the
 * checkpoint the hold ends with. Everything published while the pass runs sits above it and is
 * delivered when consumption begins.</p>
 *
 * <p>What the hold does cost is retention: the results published during it have to still be in the
 * stream when consumption starts, where a consumer that kept running would have applied them directly.
 * That window is the startup pass and nothing else, and a result the stream has since dropped is not
 * silently lost either way - it is the same retention gap the live path asks the mode's basis about,
 * and a basis that cannot answer it leaves the checkpoint where it is and says so loudly.</p>
 *
 * <p>It is also not a distributed boundary. It orders two things inside one JVM, which is the whole of
 * what "the mode's recovery runs before its consumer" means here; two instances are still two, and the
 * deployment's single {@code batch-role} is still the only thing that prevents it - see
 * {@code ARCHITECTURE.md} §3.5.</p>
 *
 * <h2>Two states, not one</h2>
 *
 * <p>"The history is covered" and "the action waiting on it succeeded" are different facts, and this
 * class tells them apart. Coverage is a statement about the mode's basis and is written once; what was
 * waiting on it is a statement about a listener container and may fail - a broker that is not reachable
 * yet, a checkpoint read that throws. The first is not undone by the second: the history really is
 * covered, and re-reporting it would be a lie about a pass that has already run.</p>
 *
 * <p>So an action that threw stays waiting. The exception is not swallowed - it is the pass's own signal
 * that what it released could not start - but the action is kept, and the next report of the boundary
 * runs it again. That report is the retry: in {@code redis-seq} every covering periodic check reports
 * the boundary, which is one interval after any transient failure.</p>
 */
@Component
@Slf4j
public class ContestScoreboardRecoveryCutover {

    /**
     * Actions that have not been served yet, in registration order.
     *
     * <p>Guarded by {@code this}. An action is in this list exactly while it has not succeeded: it is
     * taken off by the report that serves it, and put back by that same report when it failed. Taking
     * and clearing happen in one {@code synchronized} block, so two reports cannot both take the same
     * action and run it - the reports are independent triggers ({@code redis-seq} has three, and two of
     * them are on the scheduler thread) and a second start of one listener container is the mistake this
     * whole boundary exists to prevent.</p>
     *
     * <p>One entry per registration. A restart inside a live context registers again, so a consumer whose
     * action had failed can be represented twice for a while; both entries run, and the stale one is
     * harmless because the action itself is guarded by the lifecycle's own state.</p>
     */
    private final List<Runnable> awaiting = new ArrayList<>();
    private boolean covered;

    /**
     * Runs the action once the mode's history recovery reports the restored history covered, or at once
     * if it already has.
     *
     * <p>The decision to run now and the registration are taken under the same lock as
     * {@link #markCovered(String)}, so an action cannot be registered in the instant between coverage
     * being recorded and the waiting list being taken - which would be an action that never ran, and a
     * consumer that never started.</p>
     *
     * <p>An action registered after the boundary was crossed may still fail, and is treated exactly as
     * one released by {@code markCovered}: it is put back on the list, and the failure is thrown to the
     * caller rather than swallowed here. A lifecycle that could not bring its container up has to say
     * so.</p>
     */
    public void whenCovered(Runnable action) {
        synchronized (this) {
            if (!covered) {
                awaiting.add(action);
                return;
            }
        }
        // Outside the lock: the action starts a message listener container, and running that under a
        // monitor the reporting pass also takes would put the two on each other's critical path.
        RuntimeException failure = release(List.of(action), "the history recovery that had already run");
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Reports that the mode's own history recovery has run over the history the restored scoreboard was
     * missing, and releases whatever is waiting on it.
     *
     * <p>Called by the pass itself rather than inferred from it, because only the pass knows it has run:
     * the startup triggers are the ones that can say so, and each says it for its own basis.</p>
     *
     * <p>Reporting twice is ordinary - {@code redis-seq} reports from every covering check, not only the
     * startup one - and it is also the retry: whatever is still waiting is run again. What is no longer
     * waiting is not run twice, because success is what keeps it off the list.</p>
     *
     * <p>An action that threw stays waiting and its exception is thrown here once the others have had
     * their turn. Swallowing it would leave the caller reporting a boundary it released nothing on;
     * keeping the action is the other half, and it is what makes the next report a retry rather than a
     * no-op.</p>
     *
     * @param coveredBy the pass, named as the startup log would name it, so the boundary can be read
     *                  back from the log when consumption began later than an operator expected
     */
    public void markCovered(String coveredBy) {
        List<Runnable> released;
        synchronized (this) {
            covered = true;
            released = List.copyOf(awaiting);
            // Taken and cleared together: a second report that ran concurrently takes nothing, so it
            // cannot run what this one is running. release() puts back whatever did not succeed.
            awaiting.clear();
        }
        if (released.isEmpty()) {
            // No consumer is waiting on this boundary: the pass ran on an instance that does not consume
            // the stream, which is an ordinary deployment. Nothing to report at INFO.
            log.debug("Scoreboard history recovery is covered by {}; nothing is waiting on the boundary",
                    coveredBy);
            return;
        }
        log.info("Scoreboard history recovery is covered by {}; releasing {} deferred action(s)",
                coveredBy, released.size());
        RuntimeException failure = release(released, coveredBy);
        if (failure != null) {
            throw failure;
        }
    }

    /** Whether the mode's history recovery has reported the restored history covered. */
    public boolean isCovered() {
        synchronized (this) {
            return covered;
        }
    }

    /**
     * How many actions are still waiting on the boundary, including ones that failed.
     *
     * <p>Visible rather than inferred from the actions' own side effects, because "a failed action was
     * not lost" is exactly the claim that cannot be read off a consumer that never started.</p>
     */
    int awaiting() {
        synchronized (this) {
            return awaiting.size();
        }
    }

    /**
     * Runs each action once, putting back the ones that did not succeed and reporting the first failure.
     *
     * <p>Deliberately not all-or-nothing: the waiters are independent starts, one per consumer, and a
     * release that dropped the rest because the first one threw would lose what this class promises to
     * keep. Each failure is logged where it happens, so the action that is still waiting and the reason
     * it is are both readable without the caller's stack trace.</p>
     *
     * <p>The bookkeeping is in a {@code finally} because the actions were taken off the list by the
     * caller. An {@link Error} leaving the loop would otherwise take the untried actions with it, and
     * leave an action that had already succeeded on nobody's list or off everyone's - either way the next
     * report would run a container start that already happened, or lose one that had not.</p>
     *
     * @return the first failure, with the others suppressed, or {@code null} when every action succeeded
     */
    private RuntimeException release(List<Runnable> actions, String coveredBy) {
        List<Runnable> succeeded = new ArrayList<>(actions.size());
        RuntimeException firstFailure = null;
        try {
            for (Runnable action : actions) {
                try {
                    action.run();
                    succeeded.add(action);
                } catch (RuntimeException failure) {
                    log.error("A deferred action released by the scoreboard history recovery boundary ({}) "
                                    + "failed and stays waiting; whatever it starts stays stopped until the "
                                    + "boundary is reported again",
                            coveredBy, failure);
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else {
                        firstFailure.addSuppressed(failure);
                    }
                }
            }
        } finally {
            List<Runnable> unserved = new ArrayList<>(actions);
            unserved.removeAll(succeeded);
            if (!unserved.isEmpty()) {
                synchronized (this) {
                    awaiting.addAll(unserved);
                }
            }
        }
        if (firstFailure != null && succeeded.isEmpty()) {
            log.error("Scoreboard history recovery is covered by {}, but none of the {} deferred action(s) "
                    + "waiting on it could run", coveredBy, actions.size());
        }
        return firstFailure;
    }
}
