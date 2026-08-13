package my.oj.web.contest.scoreboard.stream;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes live application, automatic gap recovery, and operator-triggered rebuilds. */
@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
class ContestScoreboardStreamProcessingLock {

    private final ReentrantLock lock = new ReentrantLock();

    <T> T withLock(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    void withLock(Runnable work) {
        withLock(() -> {
            work.run();
            return null;
        });
    }
}
