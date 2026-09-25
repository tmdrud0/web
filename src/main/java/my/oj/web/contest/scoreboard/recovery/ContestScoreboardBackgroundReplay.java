package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategy.PassKind;

import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs {@code full-replay}'s rollback replay on a thread of its own, so the live consumer is never the
 * thread that waits for it.
 *
 * <h2>What a request promises</h2>
 *
 * <p>A request is served by a pass that <em>starts after</em> the request was accepted. That is the
 * whole guarantee, and it is enough: a pass started after the rollback reads MySQL after the rollback,
 * and MySQL holds every result that reached the stream before it (the judge writes MySQL first). The
 * pass is retried - after a failure, or while another pass holds the gate - until one completes, so an
 * accepted request is not dropped on a transient error.</p>
 *
 * <p>Requests are coalesced. One rollback is asked about twice, by the supervisor and by the first live
 * delivery above the rolled-back checkpoint, and both name it by the same pair of offsets - the
 * checkpoint Redis now holds and the highest offset this JVM applied - so the second is recognised and
 * adds nothing. Requests for different rollbacks that arrive before the next pass starts are merged into
 * it: their contests are unioned, and a request for every contest makes the pass cover every contest.</p>
 *
 * <p>What the gate keeps apart is unchanged: a background pass takes {@link PassKind#MYSQL_REPLAY} like
 * every other replay, so it never overlaps the startup replay or an operator's pass.</p>
 */
@Slf4j
public class ContestScoreboardBackgroundReplay implements AutoCloseable {

    /** How many recent rollbacks are remembered for coalescing. A rollback is a rare event. */
    private static final int REMEMBERED_ROLLBACKS = 1024;

    private record RollbackKey(long checkpointOffset, long highestAppliedOffset) {
    }

    private final ContestScoreboardFullReplayService replayService;
    private final ContestScoreboardRecoveryPassGate gate;
    private final long retryBackoffMillis;
    private final Object monitor = new Object();
    private final Set<RollbackKey> accepted = new LinkedHashSet<>();
    private final Thread worker;

    /** Contests the next pass must cover. Empty with {@link #pendingAll} false means no pass is due. */
    private final Set<Long> pendingContests = new TreeSet<>();
    private boolean pendingAll;
    private boolean pending;
    private boolean running;
    private boolean closed;
    private long completedPasses;

    public ContestScoreboardBackgroundReplay(ContestScoreboardFullReplayService replayService,
                                             ContestScoreboardRecoveryPassGate gate,
                                             Duration retryBackoff) {
        this.replayService = replayService;
        this.gate = gate;
        this.retryBackoffMillis = Math.max(1L, retryBackoff.toMillis());
        this.worker = new Thread(this::runWorker, "scoreboard-full-replay");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Accepts one rollback's replay.
     *
     * @param contests the contests to replay, or {@code null} for every contest with a stored result
     * @return false only when this replayer has been closed, so nothing will serve the request
     */
    public boolean request(long checkpointOffset, long highestAppliedOffset, Collection<Long> contests) {
        synchronized (monitor) {
            if (closed) {
                return false;
            }
            RollbackKey key = new RollbackKey(checkpointOffset, highestAppliedOffset);
            if (!accepted.add(key)) {
                return true;
            }
            if (accepted.size() > REMEMBERED_ROLLBACKS) {
                Iterator<RollbackKey> oldest = accepted.iterator();
                oldest.next();
                oldest.remove();
            }
            if (contests == null) {
                pendingAll = true;
            } else {
                pendingContests.addAll(contests);
            }
            pending = true;
            log.warn("Scoreboard rollback to checkpoint {} (applied through {}) queued for a background replay of {}",
                    checkpointOffset, highestAppliedOffset, contests == null ? "every contest" : "contests " + contests);
            monitor.notifyAll();
            return true;
        }
    }

    /** Waits until no pass is due or running. For tests and for an orderly shutdown. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (pending || running) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                monitor.wait(Math.max(1L, remaining / 1_000_000L));
            }
            return true;
        }
    }

    public long completedPasses() {
        synchronized (monitor) {
            return completedPasses;
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
        worker.interrupt();
        try {
            worker.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWorker() {
        while (true) {
            Set<Long> contests;
            boolean all;
            synchronized (monitor) {
                while (!pending && !closed) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        if (closed) {
                            return;
                        }
                    }
                }
                if (closed) {
                    return;
                }
                // Taken as a whole: whatever arrives from here on is served by the next pass, which
                // starts after it.
                contests = new TreeSet<>(pendingContests);
                all = pendingAll;
                pendingContests.clear();
                pendingAll = false;
                pending = false;
                running = true;
            }
            try {
                runUntilComplete(all ? null : contests);
            } finally {
                synchronized (monitor) {
                    running = false;
                    monitor.notifyAll();
                }
            }
        }
    }

    private void runUntilComplete(Set<Long> contests) {
        while (true) {
            synchronized (monitor) {
                if (closed) {
                    // The history is still in MySQL, and a JVM that starts under full-replay replays it
                    // before consuming anything.
                    log.warn("Background scoreboard replay abandoned at shutdown; the startup replay of the next "
                            + "JVM repairs what it had not reached");
                    return;
                }
            }
            try {
                boolean ran = gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
                    int offered = contests == null
                            ? replayService.replayAllContestsNewestFirst()
                            : replayService.replayContestsNewestFirst(contests);
                    log.warn("Background scoreboard replay offered {} stored result(s) of {}", offered,
                            contests == null ? "every contest" : "contests " + contests);
                    return Boolean.TRUE;
                }).isPresent();
                if (ran) {
                    synchronized (monitor) {
                        completedPasses++;
                    }
                    return;
                }
            } catch (RuntimeException failure) {
                log.error("Background scoreboard replay failed; retrying in {} ms", retryBackoffMillis, failure);
            }
            try {
                Thread.sleep(retryBackoffMillis);
            } catch (InterruptedException e) {
                synchronized (monitor) {
                    if (closed) {
                        return;
                    }
                }
            }
        }
    }
}
