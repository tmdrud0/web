package my.oj.web.contest.scoreboard;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes everything that writes the scoreboard: live stream application, automatic gap
 * recovery, operator-triggered rebuilds, and the recovery modes' replays.
 *
 * <p>It lives outside the stream package and is not conditional on the stream consumer, because a
 * recovery mode has to be able to replay whether or not this JVM consumes the broker stream.</p>
 */
@Component
public class ContestScoreboardApplyLock {

    private final ReentrantLock lock = new ReentrantLock();

    public <T> T withLock(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(Runnable work) {
        withLock(() -> {
            work.run();
            return null;
        });
    }
}
