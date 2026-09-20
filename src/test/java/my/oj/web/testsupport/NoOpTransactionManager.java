package my.oj.web.testsupport;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager that demarcates nothing, for a test whose subject is not the transaction.
 *
 * <p>{@code ContestSubmissionBatchExecutor} takes its transaction boundaries from a
 * {@link PlatformTransactionManager}, so a test that wants the callbacks to run inline - on whatever
 * connection the surrounding test has already bound - has to supply one that does nothing. The
 * alternative, the real manager, is not neutral: a test running inside {@code @DataJpaTest}'s own
 * transaction would have its work suspended into a second transaction that cannot see the rows the
 * test has not committed yet, and every assertion about what the code wrote would be about a
 * database that was never there.</p>
 *
 * <p>Nothing here proves anything about transaction boundaries, and a test that uses it must not
 * claim to. That claim needs a manager that really marks the thread; see
 * {@code ContestScoreboardReplayTransactionBoundaryTests}.</p>
 */
public class NoOpTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        // Nothing was started, so there is nothing to commit.
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        // Nothing was started, so there is nothing to roll back.
    }
}
