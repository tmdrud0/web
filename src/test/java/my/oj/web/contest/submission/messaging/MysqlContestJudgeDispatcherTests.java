package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlContestJudgeDispatcherTests {

    private MysqlContestJudgeDispatcher dispatcher;

    @AfterEach
    void closeDispatcher() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void claimsOnlyRemainingCapacityAndDoesNotClaimAgainWhileReserved() throws Exception {
        ContestJudgeOutboxStore store = mock(ContestJudgeOutboxStore.class);
        ContestSubmissionJudgeProcessor processor = mock(ContestSubmissionJudgeProcessor.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(processor).judge(org.mockito.ArgumentMatchers.anyLong());
        List<ContestJudgeOutboxStore.ClaimedEvent> claimed = List.of(
                event(1), event(2), event(3));
        when(store.claim(3, Duration.ofSeconds(30))).thenReturn(claimed);
        when(store.completeAll(anyList(), anyList()))
                .thenReturn(new ContestJudgeOutboxStore.BatchCompletionResult(1, 1, 0, 0));
        dispatcher = dispatcher(store, processor, 1, 10, 3);

        dispatcher.poll();
        await().untilAsserted(() -> assertThat(dispatcher.reservedCount()).isEqualTo(3));
        dispatcher.poll();

        verify(store).claim(3, Duration.ofSeconds(30));
        release.countDown();
        await().untilAsserted(() -> assertThat(dispatcher.reservedCount()).isZero());
    }

    @Test
    void nextClaimIsLimitedToPartiallyRemainingCapacity() throws Exception {
        ContestJudgeOutboxStore store = mock(ContestJudgeOutboxStore.class);
        ContestSubmissionJudgeProcessor processor = mock(ContestSubmissionJudgeProcessor.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(processor).judge(org.mockito.ArgumentMatchers.anyLong());
        when(store.claim(2, Duration.ofSeconds(30))).thenReturn(List.of(event(1), event(2)));
        when(store.claim(1, Duration.ofSeconds(30))).thenReturn(List.of(event(3)));
        when(store.completeAll(anyList(), anyList()))
                .thenReturn(new ContestJudgeOutboxStore.BatchCompletionResult(1, 1, 0, 0));
        dispatcher = dispatcher(store, processor, 1, 2, 3);

        dispatcher.poll();
        await().untilAsserted(() -> assertThat(dispatcher.reservedCount()).isEqualTo(2));
        dispatcher.poll();

        verify(store).claim(2, Duration.ofSeconds(30));
        verify(store).claim(1, Duration.ofSeconds(30));
        await().untilAsserted(() -> assertThat(dispatcher.reservedCount()).isEqualTo(3));
        release.countDown();
        await().untilAsserted(() -> assertThat(dispatcher.reservedCount()).isZero());
    }

    @Test
    void judgeFailureReturnsClaimToPending() {
        ContestJudgeOutboxStore store = mock(ContestJudgeOutboxStore.class);
        ContestSubmissionJudgeProcessor processor = mock(ContestSubmissionJudgeProcessor.class);
        ContestJudgeOutboxStore.ClaimedEvent event = event(7);
        when(store.claim(1, Duration.ofSeconds(30))).thenReturn(List.of(event));
        org.mockito.Mockito.doThrow(new IllegalStateException("judge down"))
                .when(processor).judge(event.submissionId());
        when(store.completeAll(anyList(), anyList()))
                .thenReturn(new ContestJudgeOutboxStore.BatchCompletionResult(0, 0, 1, 1));
        dispatcher = dispatcher(store, processor, 1, 1, 1);

        dispatcher.poll();

        await().untilAsserted(() -> verify(store).completeAll(
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.argThat(failures -> failures.size() == 1
                        && failures.get(0).event().equals(event)
                        && failures.get(0).error().contains("judge down"))));
        verify(store, never()).completeAll(org.mockito.ArgumentMatchers.eq(List.of(event)),
                org.mockito.ArgumentMatchers.eq(List.of()));
    }

    private MysqlContestJudgeDispatcher dispatcher(ContestJudgeOutboxStore store,
                                                    ContestSubmissionJudgeProcessor processor,
                                                    int workers,
                                                    int batch,
                                                    int maxInFlight) {
        MysqlContestJudgeMetrics metrics = new MysqlContestJudgeMetrics();
        return new MysqlContestJudgeDispatcher(
                store,
                processor,
                new MysqlContestJudgeProperties(
                        workers, batch, maxInFlight, Duration.ofSeconds(30), Duration.ofMillis(100)),
                metrics);
    }

    private static ContestJudgeOutboxStore.ClaimedEvent event(long id) {
        return new ContestJudgeOutboxStore.ClaimedEvent(id, id * 10, "token-" + id);
    }
}
