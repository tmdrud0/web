package my.oj.web.contest.submission.judge;

import my.oj.web.contest.submission.core.ContestSubmissionJudgeProjection;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ContestSubmissionJudgeResultBatchWriterTests {

    private final ExecutorService callers = Executors.newFixedThreadPool(4);
    private final ContestSubmissionJudgeResultStreamPublisher streamPublisher =
            mock(ContestSubmissionJudgeResultStreamPublisher.class);
    private ContestSubmissionJudgeResultBatchWriter writer;

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
        callers.shutdownNow();
    }

    @Test
    void combinesConcurrentResultsIntoOneBatch() throws Exception {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        AtomicReference<List<ContestSubmissionJudgeResultCommand>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        }).when(persistence).persistAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(4, 1, 8, Duration.ofMillis(100))
        );
        writer.start();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            long submissionId = id;
            futures.add(callers.submit(() -> writer.persist(
                    projection(submissionId),
                    SubmissionResult.PARTIAL_ACCEPTED,
                    LocalDateTime.now()
            )));
        }

        for (Future<?> future : futures) {
            future.get(3, TimeUnit.SECONDS);
        }

        verify(persistence, times(1)).persistAll(anyList());
        verify(streamPublisher, times(1)).publishAll(anyList());
        assertThat(captured.get()).hasSize(4);
    }

    @Test
    void listenerWaitsUntilBatchPersistenceReturns() throws Exception {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        doAnswer(invocation -> {
            persistenceStarted.countDown();
            assertThat(releasePersistence.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(persistence).persistAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 1, 1, Duration.ZERO)
        );
        writer.start();

        Future<?> listener = callers.submit(() -> writer.persist(
                projection(1L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        ));

        assertThat(persistenceStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.isDone()).isFalse();
        releasePersistence.countDown();
        listener.get(3, TimeUnit.SECONDS);
    }

    @Test
    void listenerWaitsUntilStreamPublishReturnsAfterPersistence() throws Exception {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        CountDownLatch publishStarted = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishStarted.countDown();
            assertThat(releasePublish.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(streamPublisher).publishAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 1, 1, Duration.ZERO)
        );
        writer.start();

        Future<?> listener = callers.submit(() -> writer.persist(
                projection(1L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        ));

        assertThat(publishStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.isDone()).isFalse();
        inOrder(persistence, streamPublisher).verify(persistence).persistAll(anyList());
        releasePublish.countDown();
        listener.get(3, TimeUnit.SECONDS);
    }

    @Test
    void processesBatchesOnConfiguredWorkersConcurrently() throws Exception {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        doAnswer(invocation -> {
            workersStarted.countDown();
            assertThat(releaseWorkers.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(persistence).persistAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 2, 2, Duration.ZERO)
        );
        writer.start();

        Future<?> first = callers.submit(() -> writer.persist(
                projection(1L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        ));
        Future<?> second = callers.submit(() -> writer.persist(
                projection(2L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        ));

        assertThat(workersStarted.await(3, TimeUnit.SECONDS)).isTrue();
        releaseWorkers.countDown();
        first.get(3, TimeUnit.SECONDS);
        second.get(3, TimeUnit.SECONDS);
        verify(persistence, times(2)).persistAll(anyList());
    }

    @Test
    void propagatesBatchFailureToListener() {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        RuntimeException failure = new RuntimeException("commit failed");
        doThrow(failure).when(persistence).persistAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 1, 1, Duration.ZERO)
        );
        writer.start();

        assertThatThrownBy(() -> writer.persist(
                projection(1L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        )).isSameAs(failure);
    }

    @Test
    void propagatesStreamPublishFailureToListenerAfterPersistence() {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        RuntimeException failure = new RuntimeException("confirm failed");
        doThrow(failure).when(streamPublisher).publishAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 1, 1, Duration.ZERO)
        );
        writer.start();

        assertThatThrownBy(() -> writer.persist(
                projection(1L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        )).isSameAs(failure);
        verify(persistence).persistAll(anyList());
    }

    @Test
    void republishSkipsPersistenceAndPublishesStoredCommand() {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 1, 1, Duration.ZERO)
        );
        writer.start();
        ContestSubmissionJudgeResultCommand stored = command(91L, LocalDateTime.now());

        writer.republish(stored);

        verifyNoInteractions(persistence);
        verify(streamPublisher).publishAll(List.of(stored));
    }

    @Test
    void mixedBatchPersistsOnlyNewResultAndPublishesBothCommands() throws Exception {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        AtomicReference<List<ContestSubmissionJudgeResultCommand>> persisted = new AtomicReference<>();
        AtomicReference<List<ContestSubmissionJudgeResultCommand>> published = new AtomicReference<>();
        doAnswer(invocation -> {
            persisted.set(invocation.getArgument(0));
            return null;
        }).when(persistence).persistAll(anyList());
        doAnswer(invocation -> {
            published.set(invocation.getArgument(0));
            return null;
        }).when(streamPublisher).publishAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(2, 1, 2, Duration.ofMillis(100))
        );
        writer.start();
        ContestSubmissionJudgeResultCommand stored = command(91L, LocalDateTime.now());

        Future<?> newResult = callers.submit(() -> writer.persist(
                projection(92L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        ));
        Future<?> replay = callers.submit(() -> writer.republish(stored));

        newResult.get(3, TimeUnit.SECONDS);
        replay.get(3, TimeUnit.SECONDS);
        assertThat(persisted.get()).extracting(ContestSubmissionJudgeResultCommand::submissionId)
                .containsExactly(92L);
        assertThat(published.get()).extracting(ContestSubmissionJudgeResultCommand::submissionId)
                .containsExactlyInAnyOrder(91L, 92L);
    }

    @Test
    void shutdownWaitsForInFlightPersistence() throws Exception {
        JdbcContestSubmissionJudgeResultBatchPersistence persistence =
                mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            persistenceStarted.countDown();
            assertThat(releasePersistence.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(persistence).persistAll(anyList());
        writer = new ContestSubmissionJudgeResultBatchWriter(
                persistence,
                streamPublisher,
                properties(1, 1, 1, Duration.ZERO)
        );
        writer.start();

        Future<?> listener = callers.submit(() -> writer.persist(
                projection(1L),
                SubmissionResult.PARTIAL_ACCEPTED,
                LocalDateTime.now()
        ));
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Future<?> shutdown = callers.submit(() -> {
            shutdownStarted.countDown();
            writer.shutdown();
        });
        assertThat(shutdownStarted.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> shutdown.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            releasePersistence.countDown();
        }

        listener.get(2, TimeUnit.SECONDS);
        shutdown.get(2, TimeUnit.SECONDS);
    }

    private static ContestSubmissionJudgeProjection projection(long submissionId) {
        ContestSubmissionJudgeProjection projection = mock(ContestSubmissionJudgeProjection.class);
        given(projection.getSubmissionId()).willReturn(submissionId);
        given(projection.getContestId()).willReturn(10L);
        given(projection.getProblemId()).willReturn(20L);
        given(projection.getUserId()).willReturn(30L);
        given(projection.getSubmittedTime()).willReturn(LocalDateTime.now());
        given(projection.getCode()).willReturn("code");
        return projection;
    }

    private static ContestSubmissionJudgeResultCommand command(long submissionId, LocalDateTime judgedAt) {
        return new ContestSubmissionJudgeResultCommand(
                submissionId,
                10L,
                20L,
                30L,
                judgedAt.minusHours(2),
                judgedAt.minusMinutes(1),
                SubmissionResult.PARTIAL_ACCEPTED,
                judgedAt
        );
    }

    private static ContestSubmissionJudgeResultWriterProperties properties(int batchSize,
                                                                            int workerCount,
                                                                            int queueCapacity,
                                                                            Duration maxWait) {
        return new ContestSubmissionJudgeResultWriterProperties(
                batchSize,
                workerCount,
                queueCapacity,
                maxWait
        );
    }
}
