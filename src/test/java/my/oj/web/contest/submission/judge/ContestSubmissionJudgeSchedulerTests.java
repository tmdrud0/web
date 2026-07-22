package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContestSubmissionJudgeSchedulerTests {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void workerCompletionTriggersImmediateRepollWhenCapacityOpens() throws InterruptedException {
        ContestSubmissionRepository repository = mock(ContestSubmissionRepository.class);
        ContestSubmissionJudgeProcessor processor = mock(ContestSubmissionJudgeProcessor.class);

        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();

        AtomicInteger pollCount = new AtomicInteger();
        when(repository.findTopUnjudgedSubmissionIds(anyInt())).thenAnswer(invocation -> {
            int attempt = pollCount.incrementAndGet();
            if (attempt == 1) {
                return List.of(1L);
            }
            if (attempt == 2) {
                return List.of(2L);
            }
            return List.of();
        });

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondProcessed = new CountDownLatch(1);

        org.mockito.Mockito.doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            if (id == 1L) {
                firstStarted.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            }
            if (id == 2L) {
                secondProcessed.countDown();
            }
            return null;
        }).when(processor).judge(org.mockito.ArgumentMatchers.anyLong());

        ContestSubmissionJudgeScheduler scheduler =
                new ContestSubmissionJudgeScheduler(repository, processor, executor);

        scheduler.triggerImmediatePollForTest();

        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pollCount.get()).isEqualTo(1);

        releaseFirst.countDown();

        assertThat(secondProcessed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pollCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(scheduler.inFlightCount()).isZero();
        assertThat(scheduler.isPolling()).isFalse();
    }
}
