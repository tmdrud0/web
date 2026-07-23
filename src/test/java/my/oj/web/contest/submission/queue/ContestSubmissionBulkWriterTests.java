package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionWriteRequest;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                dispatcher,
                new ContestSubmissionBulkProperties(1, 1, 2000)
        );
        var stage = writer.saveAsync(new ContestSubmissionWriteRequest(
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

    @Test
    void saveAsync_rejectsAtCapacityAndRecoversAfterCommittedCompletion() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionCompletionDispatcher dispatcher = immediateDispatcher();
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        ContestSubmissionService.ContestSubmissionCreateResult expected = result(101L);
        given(processor.process(anyList())).willAnswer(invocation -> {
            started.countDown();
            assertThat(allowCompletion.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of(expected);
        });

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                dispatcher,
                new ContestSubmissionBulkProperties(1, 1, 1)
        );

        var first = writer.saveAsync(request(101L));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        var rejected = writer.saveAsync(request(102L));
        assertThatThrownBy(() -> rejected.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionOverloadedException.class);

        allowCompletion.countDown();
        assertThat(first.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(expected);
        assertThat(writer.saveAsync(request(103L)).toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isSameAs(expected);
        assertThat(metrics.snapshot().rejectedSubmissionCount()).isEqualTo(1);
        assertThat(metrics.snapshot().currentInFlight()).isZero();
        assertThat(metrics.snapshot().maxInFlight()).isEqualTo(1);
    }

    @Test
    void saveAsync_releasesAdmissionAfterProcessingFailure() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        ContestSubmissionService.ContestSubmissionCreateResult expected = result(104L);
        given(processor.process(anyList()))
                .willThrow(new IllegalStateException("database failed"))
                .willReturn(List.of(expected));

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                immediateDispatcher(),
                new ContestSubmissionBulkProperties(1, 1, 1)
        );

        var failed = writer.saveAsync(request(104L));
        assertThatThrownBy(() -> failed.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database failed");

        assertThat(writer.saveAsync(request(105L)).toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isSameAs(expected);
        assertThat(metrics.snapshot().currentInFlight()).isZero();
    }

    @Test
    void saveAsync_releasesAdmissionWhenCompletionDispatcherRejects() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionCompletionDispatcher dispatcher = mock(ContestSubmissionCompletionDispatcher.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        ContestSubmissionService.ContestSubmissionCreateResult expected = result(106L);
        given(processor.process(anyList())).willReturn(List.of(expected));
        doAnswer(invocation -> {
            throw new IllegalStateException("completion unavailable");
        }).when(dispatcher).dispatch(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                dispatcher,
                new ContestSubmissionBulkProperties(1, 1, 1)
        );

        assertThat(writer.saveAsync(request(106L)).toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isSameAs(expected);
        assertThat(writer.saveAsync(request(107L)).toCompletableFuture().get(2, TimeUnit.SECONDS))
                .isSameAs(expected);
        assertThat(metrics.snapshot().currentInFlight()).isZero();
    }

    @Test
    void shutdown_failsQueuedSubmissionsWithoutLeakingAdmission() throws Exception {
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        writer = new ContestSubmissionBulkWriter(
                mock(ContestSubmissionBulkProcessor.class),
                metrics,
                immediateDispatcher(),
                new ContestSubmissionBulkProperties(2, 1, 1)
        );

        var pending = writer.saveAsync(request(108L));
        writer.shutdown();

        assertThatThrownBy(() -> pending.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionOverloadedException.class);
        assertThat(metrics.snapshot().currentInFlight()).isZero();
    }

    private static ContestSubmissionCompletionDispatcher immediateDispatcher() {
        ContestSubmissionCompletionDispatcher dispatcher = mock(ContestSubmissionCompletionDispatcher.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(dispatcher).dispatch(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        return dispatcher;
    }

    private static ContestSubmissionWriteRequest request(long submissionId) {
        return new ContestSubmissionWriteRequest(
                1L,
                2L,
                3L,
                "code",
                "hash-" + submissionId,
                LocalDateTime.now(),
                submissionId
        );
    }

    private static ContestSubmissionService.ContestSubmissionCreateResult result(long submissionId) {
        return new ContestSubmissionService.ContestSubmissionCreateResult(
                ContestSubmission.placeholder(submissionId),
                false
        );
    }
}
