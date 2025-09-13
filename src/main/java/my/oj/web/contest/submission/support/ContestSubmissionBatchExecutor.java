package my.oj.web.contest.submission.support;

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
                transactionTemplate.executeWithoutResult(status -> batchConsumer.accept(batch));
            } else {
                batchConsumer.accept(batch);
            }
            lastProcessedId = batch.get(batch.size() - 1);
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
