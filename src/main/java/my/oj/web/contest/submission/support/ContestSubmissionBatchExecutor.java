package my.oj.web.contest.submission.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

@Component
public class ContestSubmissionBatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(ContestSubmissionBatchExecutor.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MILLIS = 50L;

    private final TransactionTemplate transactionTemplate;

    public ContestSubmissionBatchExecutor(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void processBatches(Long contestId,
                               int batchSize,
                               BatchLoader loader,
                               Consumer<List<Long>> batchConsumer) {
        process(contestId, batchSize, loader, batchConsumer, BatchTransactionMode.TRANSACTIONAL);
    }

    public void processBatchesNonTransactional(Long contestId,
                                               int batchSize,
                                               BatchLoader loader,
                                               Consumer<List<Long>> batchConsumer) {
        process(contestId, batchSize, loader, batchConsumer, BatchTransactionMode.NON_TRANSACTIONAL);
    }

    private void process(Long contestId,
                         int batchSize,
                         BatchLoader loader,
                         Consumer<List<Long>> batchConsumer,
                         BatchTransactionMode mode) {
        Long lastProcessedId = null;
        Pageable pageable = PageRequest.of(0, batchSize);
        while (true) {
            List<Long> submissionIds = loader.load(contestId, lastProcessedId, pageable);
            if (submissionIds == null || submissionIds.isEmpty()) {
                break;
            }
            List<Long> batch = List.copyOf(submissionIds);
            if (mode == BatchTransactionMode.TRANSACTIONAL) {
                executeWithRetry(() -> transactionTemplate.executeWithoutResult(status -> batchConsumer.accept(batch)));
            } else {
                executeWithRetry(() -> batchConsumer.accept(batch));
            }
            lastProcessedId = batch.get(batch.size() - 1);
        }
    }

    private void executeWithRetry(Runnable runnable) {
        int attempt = 0;
        while (true) {
            try {
                runnable.run();
                return;
            } catch (RuntimeException ex) {
                attempt++;
                if (attempt > DEFAULT_MAX_RETRIES) {
                    throw ex;
                }
                log.warn("Batch execution failed on attempt {}. Retrying...", attempt, ex);
                try {
                    Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    @FunctionalInterface
    public interface BatchLoader {
        List<Long> load(Long contestId, Long afterSubmissionId, Pageable pageable);
    }

    private enum BatchTransactionMode {
        TRANSACTIONAL,
        NON_TRANSACTIONAL
    }
}
