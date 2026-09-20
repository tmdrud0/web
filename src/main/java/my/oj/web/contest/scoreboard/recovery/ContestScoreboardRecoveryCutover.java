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
 */
@Component
@Slf4j
public class ContestScoreboardRecoveryCutover {

    /** Guarded by {@code this}: an action added before coverage must not be lost to one added after. */
    private final List<Runnable> waiting = new ArrayList<>();
    private boolean covered;

    /**
     * Runs the action once the mode's history recovery reports the restored history covered, or at once
     * if it already has.
     *
     * <p>Both halves are decided under the same lock as {@link #markCovered(String)}, so an action
     * cannot be registered in the instant between coverage being recorded and the waiting list being
     * released - which would be an action that never ran, and a consumer that never started.</p>
     */
    public void whenCovered(Runnable action) {
        synchronized (this) {
            if (!covered) {
                waiting.add(action);
                return;
            }
        }
        // Outside the lock: the action starts a message listener container, and running that under a
        // monitor the recovery pass also takes would put the two on each other's critical path.
        action.run();
    }

    /**
     * Reports that the mode's own history recovery has run over the history the restored scoreboard was
     * missing, and releases whatever was waiting on it.
     *
     * <p>Called by the pass itself rather than inferred from it, because only the pass knows it has run:
     * the startup triggers are the ones that can say so, and each says it for its own basis.</p>
     *
     * @param coveredBy the pass, named as the startup log would name it, so the boundary can be read
     *                  back from the log when consumption began later than an operator expected
     */
    public void markCovered(String coveredBy) {
        List<Runnable> released;
        synchronized (this) {
            if (covered) {
                return;
            }
            covered = true;
            released = List.copyOf(waiting);
            waiting.clear();
        }
        if (released.isEmpty()) {
            // No consumer is waiting on this boundary: the pass ran on an instance that does not consume
            // the stream, which is an ordinary deployment. Nothing to report at INFO.
            log.debug("Scoreboard history recovery is covered by {}; nothing is waiting on the boundary",
                    coveredBy);
            return;
        }
        log.info("Scoreboard history recovery is covered by {}; {} deferred action(s) released",
                coveredBy, released.size());
        released.forEach(Runnable::run);
    }

    /** Whether the mode's history recovery has reported the restored history covered. */
    public boolean isCovered() {
        synchronized (this) {
            return covered;
        }
    }
}
