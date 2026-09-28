package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRedisSequenceRecoveryService.SequenceCheckReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContestScoreboardRedisSequenceLiveRecoveryTests {

    private final ContestScoreboardRedisSequenceRecoveryService recoveryService =
            mock(ContestScoreboardRedisSequenceRecoveryService.class);
    private final ContestScoreboardRedisSequenceMetrics metrics =
            mock(ContestScoreboardRedisSequenceMetrics.class);
    private final ContestScoreboardRecoveryPassGate gate =
            new ContestScoreboardRecoveryPassGate(new SimpleMeterRegistry());
    private final QueuedExecutor executor = new QueuedExecutor();
    private final ContestScoreboardRedisSequenceLiveRecovery recovery =
            new ContestScoreboardRedisSequenceLiveRecovery(recoveryService, metrics, gate, executor);

    @Test
    void immediateTriggerIsSingleFlight() {
        when(recoveryService.check()).thenReturn(new SequenceCheckReport(1, 0, 0, false, false));

        assertThat(recovery.trigger()).isTrue();
        assertThat(recovery.trigger()).isFalse();
        assertThat(recovery.submitted()).isTrue();
        verify(recoveryService, times(2)).requestRollbackRepair();
        verify(recoveryService, never()).check();

        executor.runNext();

        verify(recoveryService).check();
        assertThat(recovery.submitted()).isFalse();
    }

    @Test
    void immediateTriggerUsesTheSharedRecoveryPassGate() {
        gate.tryRun(ContestScoreboardRecoveryStrategy.PassKind.MYSQL_REPLAY, () -> {
            assertThat(recovery.trigger()).isTrue();
            executor.runNext();
            return Boolean.TRUE;
        });

        verify(recoveryService).requestRollbackRepair();
        verify(recoveryService, never()).check();
        assertThat(recovery.submitted()).isFalse();
    }

    @Test
    void failedImmediateCheckIsObservableAndLeavesPeriodicRetryAvailable() {
        when(recoveryService.check()).thenThrow(new IllegalStateException("Redis unavailable"));

        recovery.trigger();
        executor.runNext();

        verify(metrics).recordFailedRound();
        assertThat(recovery.submitted()).isFalse();
        assertThat(recovery.trigger()).isTrue();
    }

    private static final class QueuedExecutor extends AbstractExecutorService {

        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shutdown");
            }
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
