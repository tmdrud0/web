package my.oj.web.contest.scoreboard.poll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger's SQL and transactions against a real, Flyway-migrated MySQL. Skipped where Docker is absent.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcContestScoreboardSequenceLedgerMySqlIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = MySqlPollTestDatabase.container();

    private MySqlPollTestDatabase database;
    private JdbcContestScoreboardSequenceLedger ledger;

    @BeforeEach
    void setUp() {
        database = new MySqlPollTestDatabase(MYSQL);
        database.migrateFromScratch();
        database.contest(1, 3, 2);
        ledger = new JdbcContestScoreboardSequenceLedger(database.jdbc, database.transactionManager);
    }

    /** The migration starts the watermark at the highest sequence the results already hold. */
    @Test
    void theWatermarkStartsAtTheHighestExistingSequence() {
        database.migrateFromScratchTo("18");
        database.contest(1, 1, 1);
        database.result(1, 1, 1, 1, 1, "ACCEPTED");
        database.result(2, 1, 1, 1, 2, "WRONG_ANSWER");
        database.jdbc.update("UPDATE contest_submission_result SET scoreboard_applied_seq = 41 WHERE submission_id = 1");

        database.migrateRest();

        assertThat(new JdbcContestScoreboardSequenceLedger(database.jdbc, database.transactionManager)
                .highestDurableSequence()).isEqualTo(41L);
    }

    @Test
    void anEmptyLedgerStartsTheWatermarkAtZero() {
        assertThat(ledger.highestDurableSequence()).isZero();
        assertThat(ledger.highestUnsequencedJudgedSubmissionId()).isNull();
    }

    @Test
    void readsOnlyJudgedUnsequencedRowsInsideTheIdBounds() {
        database.result(1, 1, 1, 1, 1, "ACCEPTED");
        database.result(2, 1, 1, 2, 2, "PENDING");
        database.result(3, 1, 2, 1, 3, "WRONG_ANSWER");
        database.result(4, 1, 2, 2, 4, "ACCEPTED");
        database.result(5, 1, 3, 1, 5, "ACCEPTED");
        ledger.recordApplied(Map.of(4L, 1L));

        assertThat(ledger.highestUnsequencedJudgedSubmissionId()).isEqualTo(5L);
        assertThat(ledger.unsequencedJudgedResults(null, 3, 10))
                .extracting(ContestScoreboardSequencedResult::submissionId)
                .containsExactly(1L, 3L);
        assertThat(ledger.unsequencedJudgedResults(1L, 5, 10))
                .extracting(ContestScoreboardSequencedResult::submissionId)
                .containsExactly(3L, 5L);
        assertThat(ledger.unsequencedJudgedResults(null, 5, 1))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.contestStart()).isEqualTo(MySqlPollTestDatabase.START);
                    assertThat(row.userId()).isEqualTo(MySqlPollTestDatabase.userId(1));
                    assertThat(row.problemId()).isEqualTo(MySqlPollTestDatabase.problemId(1, 1));
                });
    }

    @Test
    void markersAndTheWatermarkCommitTogetherAndTheWatermarkNeverFalls() {
        database.result(1, 1, 1, 1, 1, "ACCEPTED");
        database.result(2, 1, 1, 2, 2, "ACCEPTED");

        ledger.recordApplied(Map.of(1L, 7L, 2L, 9L));
        assertThat(database.sequenceOf(1)).isEqualTo(7L);
        assertThat(database.sequenceOf(2)).isEqualTo(9L);
        assertThat(database.watermark()).isEqualTo(9L);

        ledger.recordApplied(Map.of(1L, 3L));
        assertThat(database.watermark()).as("GREATEST keeps the watermark from falling").isEqualTo(9L);
    }

    /**
     * A marker that fails after the watermark statement ran takes the watermark back with it - the
     * watermark never claims a sequence no result holds.
     */
    @Test
    void aFailedMarkerRollsTheWatermarkBack() {
        database.result(1, 1, 1, 1, 1, "ACCEPTED");
        ledger.recordApplied(Map.of(1L, 5L));
        Map<Long, Long> batch = new LinkedHashMap<>();
        batch.put(1L, 11L);
        batch.put(999L, 12L);   // no such result row

        assertThatThrownBy(() -> ledger.recordApplied(batch)).isInstanceOf(IllegalStateException.class);

        assertThat(database.watermark()).isEqualTo(5L);
        assertThat(database.sequenceOf(1)).isEqualTo(5L);
    }

    @Test
    void readsARangeByItsSequenceBoundsOnlyAndSkipsPending() {
        for (long id = 1; id <= 6; id++) {
            database.result(id, 1, 1 + (int) (id % 3), 1 + (int) (id % 2), (int) id, "ACCEPTED");
        }
        ledger.recordApplied(Map.of(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L));
        database.judge(5, "PENDING");

        assertThat(ledger.judgedResultsInRange(2, 5, 10))
                .extracting(ContestScoreboardSequencedResult::submissionId)
                .containsExactly(3L, 4L);
        assertThat(ledger.judgedResultsInRange(2, 5, 1))
                .extracting(ContestScoreboardSequencedResult::appliedSequence)
                .containsExactly(3L);
    }

    @Test
    void anIdenticalPendingRangeIsReusedAndCompletionComparesTheGeneration() {
        ContestScoreboardRecoveryRange first = ledger.openRange(3, 9);
        assertThat(ledger.openRange(3, 9)).isEqualTo(first);
        ContestScoreboardRecoveryRange second = ledger.openRange(4, 12);
        assertThat(second.generation()).isGreaterThan(first.generation());

        assertThat(ledger.completeRange(first.generation())).isTrue();
        assertThat(ledger.completeRange(first.generation())).isFalse();
        assertThat(ledger.pendingRanges()).containsExactly(second);

        ContestScoreboardRecoveryRange again = ledger.openRange(3, 9);
        assertThat(again.generation()).as("a completed range is not reused").isGreaterThan(second.generation());
        assertThat(database.jdbc.queryForObject("""
                SELECT COUNT(*) FROM scoreboard_sequence_recovery_range
                WHERE generation = ? AND status = 'COMPLETED' AND completed_at IS NOT NULL
                """, Integer.class, first.generation())).isEqualTo(1);
    }

    /** The named lock lets exactly one poller hold ownership, and passes on when it is released. */
    @Test
    void onlyOneInstanceHoldsThePollerLock() {
        MySqlNamedLockPollOwnership first = new MySqlNamedLockPollOwnership(database.dataSource, "poll-test-lock");
        MySqlNamedLockPollOwnership second = new MySqlNamedLockPollOwnership(database.dataSource, "poll-test-lock");
        try {
            assertThat(first.holds()).isTrue();
            assertThat(first.holds()).as("re-checked, still held").isTrue();
            assertThat(second.holds()).isFalse();

            first.release();
            assertThat(second.holds()).isTrue();
            assertThat(first.holds()).isFalse();
        } finally {
            first.release();
            second.release();
        }
    }
}
