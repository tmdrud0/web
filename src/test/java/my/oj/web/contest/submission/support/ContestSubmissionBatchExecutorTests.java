package my.oj.web.contest.submission.support;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ContestSubmissionBatchExecutorTests {

    private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();
    private final ContestSubmissionBatchExecutor executor = new ContestSubmissionBatchExecutor(transactionManager);

    @Test
    void processBatches_invokesConsumerUntilLoaderReturnsEmpty() {
        List<Long> firstBatch = List.of(1L, 2L, 3L);
        List<Long> secondBatch = List.of(10L);
        List<Long> processedIds = new ArrayList<>();
        List<Long> requestedAfterIds = new ArrayList<>();

        AtomicInteger callCount = new AtomicInteger();

        executor.processBatches(
                100L,
                3,
                (contestId, afterId, pageable) -> {
                    requestedAfterIds.add(afterId);
                    int count = callCount.getAndIncrement();
                    if (count == 0) {
                        return firstBatch;
                    } else if (count == 1) {
                        return secondBatch;
                    }
                    return List.of();
                },
                processedIds::addAll
        );

        assertThat(processedIds).containsExactlyElementsOf(List.of(1L, 2L, 3L, 10L));
        assertThat(requestedAfterIds).containsExactly(null, 3L, 10L);
    }

    @Test
    void processBatches_retriesTransientFailure() {
        List<Long> batch = List.of(1L, 2L);
        AtomicInteger consumerCalls = new AtomicInteger();

        executor.processBatches(
                42L,
                2,
                (contestId, afterId, pageable) -> afterId == null ? batch : List.of(),
                ids -> {
                    if (consumerCalls.getAndIncrement() == 0) {
                        throw new RuntimeException("transient failure");
                    }
                }
        );

        assertThat(consumerCalls.get()).isEqualTo(2);
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // no-op
        }
    }
}

