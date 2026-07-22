package my.oj.web.contest.submission.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ContestSubmissionCompletionDispatcherTests {

    private ContestSubmissionCompletionDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    void dispatch_usesCallerThreadWhenBoundedQueueIsFull() throws Exception {
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        dispatcher = new ContestSubmissionCompletionDispatcher(metrics, 1, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);

        dispatcher.dispatch(100, () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch(100, secondCompleted::countDown);

        AtomicReference<Thread> executionThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        dispatcher.dispatch(100, () -> executionThread.set(Thread.currentThread()));

        assertThat(executionThread.get()).isSameAs(caller);
        assertThat(metrics.snapshot().completionCallerRunsCount()).isEqualTo(1);

        releaseFirst.countDown();
        assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.shutdown();
        dispatcher = null;
        assertThat(metrics.snapshot().completionTaskCount()).isEqualTo(3);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
