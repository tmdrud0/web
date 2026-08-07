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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
    void saveAsync_failsOnlyInconsistentSubmissionsAndRetriesTheRest() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        ContestSubmissionService.ContestSubmissionCreateResult second = result(202L);
        ContestSubmissionService.ContestSubmissionCreateResult third = result(203L);
        AtomicReference<List<ContestSubmissionWriteRequest>> retried = new AtomicReference<>();
        given(processor.process(anyList()))
                .willThrow(new ContestSubmissionBatchConsistencyException("omitted", List.of(201L)))
                .willAnswer(invocation -> {
                    retried.set(invocation.getArgument(0));
                    return List.of(second, third);
                });

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                immediateDispatcher(),
                new ContestSubmissionBulkProperties(3, 1, 10)
        );

        var offending = writer.saveAsync(request(201L));
        var survivorA = writer.saveAsync(request(202L));
        var survivorB = writer.saveAsync(request(203L));

        assertThatThrownBy(() -> offending.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        assertThat(survivorA.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(second);
        assertThat(survivorB.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(third);
        assertThat(retried.get())
                .extracting(ContestSubmissionWriteRequest::reservedSubmissionId)
                .containsExactly(202L, 203L);
        assertThat(metrics.snapshot().currentInFlight()).isZero();
    }

    @Test
    void saveAsync_failsInBatchDuplicatesOfAnInconsistentSubmission() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        ContestSubmissionService.ContestSubmissionCreateResult survivor = result(303L);
        AtomicReference<List<ContestSubmissionWriteRequest>> retried = new AtomicReference<>();
        // Only the primary of the collapsed pair is reported; its duplicate carries the same user
        // and code, so replaying it would fail again.
        given(processor.process(anyList()))
                .willThrow(new ContestSubmissionBatchConsistencyException("omitted", List.of(301L)))
                .willAnswer(invocation -> {
                    retried.set(invocation.getArgument(0));
                    return List.of(survivor);
                });

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                immediateDispatcher(),
                new ContestSubmissionBulkProperties(3, 1, 10)
        );

        var offending = writer.saveAsync(request(301L, "shared-hash"));
        var collapsedDuplicate = writer.saveAsync(request(302L, "shared-hash"));
        var unrelated = writer.saveAsync(request(303L, "other-hash"));

        assertThatThrownBy(() -> offending.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        assertThatThrownBy(() -> collapsedDuplicate.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        assertThat(unrelated.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(survivor);
        assertThat(retried.get())
                .extracting(ContestSubmissionWriteRequest::reservedSubmissionId)
                .containsExactly(303L);
        assertThat(metrics.snapshot().currentInFlight()).isZero();
    }

    @Test
    void saveAsync_failsWholeChunkWhenConsistencyFailureNamesNoSubmissions() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        given(processor.process(anyList()))
                .willThrow(new ContestSubmissionBatchConsistencyException("duplicate reserved id in batch"));

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                immediateDispatcher(),
                new ContestSubmissionBulkProperties(2, 1, 10)
        );

        var first = writer.saveAsync(request(401L));
        var second = writer.saveAsync(request(402L));

        assertThatThrownBy(() -> first.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        assertThatThrownBy(() -> second.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        verify(processor, times(1)).process(anyList());
        assertThat(metrics.snapshot().currentInFlight()).isZero();
    }

    @Test
    void saveAsync_failsWholeChunkWhenEveryRowIsInconsistent() throws Exception {
        ContestSubmissionBulkProcessor processor = mock(ContestSubmissionBulkProcessor.class);
        ContestSubmissionBulkMetrics metrics = new ContestSubmissionBulkMetrics();
        given(processor.process(anyList()))
                .willThrow(new ContestSubmissionBatchConsistencyException("omitted", List.of(501L, 502L)));

        writer = new ContestSubmissionBulkWriter(
                processor,
                metrics,
                immediateDispatcher(),
                new ContestSubmissionBulkProperties(2, 1, 10)
        );

        var first = writer.saveAsync(request(501L));
        var second = writer.saveAsync(request(502L));

        assertThatThrownBy(() -> first.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        assertThatThrownBy(() -> second.toCompletableFuture().get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);
        verify(processor, times(1)).process(anyList());
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
        return request(submissionId, "hash-" + submissionId);
    }

    private static ContestSubmissionWriteRequest request(long submissionId, String codeHash) {
        return new ContestSubmissionWriteRequest(
                1L,
                2L,
                3L,
                "code",
                codeHash,
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
