package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.submission.core.ContestScoreboardReplayRow;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.submission.SubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the database transaction begins and ends around a replay.
 *
 * <p>The failure this pins is not a crash. It is a Redis write that sits inside a database
 * transaction holding a connection - work whose two halves cannot both be undone, so a rollback
 * leaves the scoreboard and the database disagreeing while the code that wrote them looks
 * transactional. The transaction manager here really marks the thread, which is the only way the
 * question can be asked at all: a no-op manager reports "no transaction" for everything, and a test
 * built on one would pass just as happily with the bug restored.</p>
 *
 * <p>Both halves are asserted from the one replay, because they are each other's control. The
 * scoreboard sees no transaction; the marker sees one. Either assertion alone is satisfied by a
 * manager that does nothing.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContestScoreboardReplayTransactionBoundaryTests {

    private static final long CONTEST_ID = 77L;

    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestScoreboardAppliedMarker appliedMarker;

    private final List<Boolean> transactionOpenWhenScoreboardWritten = new ArrayList<>();
    private final List<Boolean> transactionOpenWhenMarkerWritten = new ArrayList<>();

    private ContestScoreboardFullReplayService replayService;

    @BeforeEach
    void setUp() {
        ContestScoreboardApplier scoreboardApplier = mock(ContestScoreboardApplier.class);
        when(scoreboardApplier.applyAll(anyList())).thenAnswer(invocation -> {
            transactionOpenWhenScoreboardWritten.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
            List<ContestScoreboardApplier.ApplyRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.success(request.correlationId(), null))
                    .toList();
        });
        doAnswer(invocation -> {
            transactionOpenWhenMarkerWritten.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(appliedMarker).markApplied(anyList());

        ContestSubmissionBatchExecutor batchExecutor =
                new ContestSubmissionBatchExecutor(new MarkingTransactionManager());
        replayService = new ContestScoreboardFullReplayService(
                resultRepository,
                batchExecutor,
                new ContestScoreboardReplayApplication(
                        scoreboardApplier,
                        appliedMarker,
                        new ContestScoreboardApplyLock(),
                        batchExecutor,
                        new SimpleMeterRegistry()
                ),
                properties()
        );
    }

    /**
     * The scoreboard script runs on the connection pool's terms, not a transaction's: nothing is
     * rolled back here by anything, so holding a database connection across it buys nothing and
     * costs a connection for the length of an {@code EVAL}.
     */
    @Test
    void theScoreboardIsWrittenWithNoDatabaseTransactionOpen() {
        replayOneStoredResult();

        assertThat(transactionOpenWhenScoreboardWritten)
                .as("the scoreboard script must not run inside a database transaction")
                .containsExactly(false);
    }

    /**
     * And the other half, which is what stops the assertion above from being about a manager that
     * never marks anything: the marker is recorded in a transaction that is really open.
     */
    @Test
    void theMarkerIsWrittenInsideATransactionOfItsOwn() {
        replayOneStoredResult();

        assertThat(transactionOpenWhenMarkerWritten)
                .as("the applied marker must be written in its own transaction")
                .containsExactly(true);
    }

    private void replayOneStoredResult() {
        when(resultRepository.findReplayRowsByContestId(eq(CONTEST_ID), any(), any(), any()))
                .thenReturn(List.of(row(3L)))
                .thenReturn(List.of());

        assertThat(replayService.replayContest(CONTEST_ID)).isEqualTo(1);
    }

    private static ContestScoreboardRecoveryProperties properties() {
        return new ContestScoreboardRecoveryProperties(
                ContestScoreboardRecoveryMode.FULL_REPLAY,
                new ContestScoreboardRecoveryProperties.FullReplay(1000, 500, true),
                new ContestScoreboardRecoveryProperties.RedisSequence(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1000, 10, 5, 500,
                        3, Duration.ofMillis(50), true),
                new ContestScoreboardRecoveryProperties.StreamOffset(
                        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.FULL_REPLAY,
                        ContestScoreboardRecoveryProperties.StreamOffset.StartupOffset.STORED),
                new ContestScoreboardRecoveryProperties.RecoveryOwner(true)
        );
    }

    private static ContestScoreboardReplayRow row(long submissionId) {
        return new ReplayRow(submissionId);
    }

    /**
     * A transaction manager that really does what the assertions read: it marks the thread for the
     * duration of the transaction, so {@code isActualTransactionActive()} answers about the caller's
     * boundaries rather than about the manager's capabilities.
     */
    private static final class MarkingTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            // Non-null, so the status reports that there is a transaction rather than only that one
            // was requested - which is what the thread-local flag is set from.
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            // Never reused: this manager has no resources to share, so every request is a new one.
            return false;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // There is no resource to begin against.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // There is no resource to commit.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // There is no resource to roll back.
        }
    }

    /** Only the submission id takes part in the assertions. */
    private static final class ReplayRow implements ContestScoreboardReplayRow {

        private final long submissionId;

        ReplayRow(long submissionId) {
            this.submissionId = submissionId;
        }

        @Override
        public Long getSubmissionId() {
            return submissionId;
        }

        @Override
        public Long getContestId() {
            return CONTEST_ID;
        }

        @Override
        public Long getProblemId() {
            return 201L;
        }

        @Override
        public Long getUserId() {
            return 100L;
        }

        @Override
        public LocalDateTime getContestStart() {
            return LocalDateTime.of(2024, 1, 1, 9, 0);
        }

        @Override
        public LocalDateTime getSubmittedTime() {
            return LocalDateTime.of(2024, 1, 1, 10, 0);
        }

        @Override
        public SubmissionResult getResult() {
            return SubmissionResult.ACCEPTED;
        }
    }
}
