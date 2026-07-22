package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ContestSubmissionBulkWriterTests {

    private ContestSubmissionBulkWriter writer;

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    @Test
    void saveAsync_defersFutureCompletionToCompletionDispatcher() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionCompletionDispatcher dispatcher = mock(ContestSubmissionCompletionDispatcher.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        ContestSubmissionService.ContestSubmissionCreateResult expected =
                new ContestSubmissionService.ContestSubmissionCreateResult(
                        ContestSubmission.placeholder(100L),
                        false
                );
        given(processor.process(anyList())).willReturn(List.of(expected));

        AtomicReference<Runnable> capturedCompletion = new AtomicReference<>();
        CountDownLatch dispatched = new CountDownLatch(1);
        doAnswer(invocation -> {
            capturedCompletion.set(invocation.getArgument(1));
            dispatched.countDown();
            return null;
        }).when(dispatcher).dispatch(org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.any());

        writer = new ContestSubmissionBulkWriter(processor, metrics, dispatcher, 1, 1);
        var stage = writer.saveAsync(new ContestSubmissionQueueRequest(
                1L,
                2L,
                3L,
                "code",
                "hash",
                LocalDateTime.now(),
                100L
        ));

        assertThat(dispatched.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(stage.toCompletableFuture()).isNotDone();

        capturedCompletion.get().run();

        assertThat(stage.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(expected);
        assertThat(metrics.snapshot().chunkCount()).isEqualTo(1);
    }
}
