package my.oj.web.contest.submission.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /**
     * The same keyset loop, for a loader that returns whole rows rather than ids.
     *
     * <p>Paging stays bounded by {@code batchSize} no matter how many rows the scope holds, which is
     * the point: a replay must not load a contest's results into memory in one go.</p>
     *
     * <p><strong>No transaction is opened around the batch consumer</strong>, unlike
     * {@link #processBatches}. The one caller replays stored judgements onto a Redis scoreboard, so a
     * transaction here would run the script's {@code EVAL} - and the read-back of the sequence it
     * issued - inside a database transaction holding a connection. That is a boundary with nothing
     * on the other side of it: the database transaction can be rolled back and the Redis writes
     * cannot, so the two halves of a replay would be able to disagree while the loop's own retry
     * re-ran them together. Each half takes the transaction it actually needs instead, and the
     * consumer's is {@link #inNewTransaction}.</p>
     *
     * @param idOf reads the keyset column out of a row, so the next page can start after the last
     *             row of this one
     */
    public <T> void processBatchesOf(Long scopeId,
                                     int batchSize,
                                     RowLoader<T> loader,
                                     Function<T, Long> idOf,
                                     Consumer<List<T>> batchConsumer) {
        Long lastProcessedId = null;
        Pageable pageable = PageRequest.of(0, batchSize);
        while (true) {
            List<T> rows = loader.load(scopeId, lastProcessedId, pageable);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            List<T> batch = List.copyOf(rows);
            executeWithRetry(() -> batchConsumer.accept(batch));
            lastProcessedId = idOf.apply(batch.get(batch.size() - 1));
        }
    }

    /**
     * One short transaction, for the database half of a step whose Redis half has already happened.
     *
     * <p>Always a new one, and never spanning the call that did the Redis work: the caller writes the
     * scoreboard first and records that it did so afterwards, so what this transaction commits is
     * evidence about a write that is already durable and cannot be taken back.</p>
     */
    public void inNewTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
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

    /**
     * The same retry discipline with an operator's own bounds, for a caller whose attempts and
     * backoff are configuration rather than a constant.
     *
     * @param maxAttempts total attempts, so a value of one means no retry at all
     */
    public void executeWithRetry(Runnable runnable, int maxAttempts, Duration backoff) {
        int attempt = 1;
        while (true) {
            try {
                runnable.run();
                return;
            } catch (RuntimeException ex) {
                if (attempt >= Math.max(1, maxAttempts)) {
                    throw ex;
                }
                log.warn("Batch execution failed on attempt {} of {}. Retrying...", attempt, maxAttempts, ex);
                try {
                    Thread.sleep(Math.max(0L, backoff.toMillis()) * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                attempt++;
            }
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

    @FunctionalInterface
    public interface RowLoader<T> {
        List<T> load(Long scopeId, Long afterId, Pageable pageable);
    }

    private enum BatchTransactionMode {
        TRANSACTIONAL,
        NON_TRANSACTIONAL
    }
}
