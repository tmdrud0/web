package my.oj.web.contest.finalization;

import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.accepted.AcceptedSubmission;
import my.oj.web.user.rank.solved.SolvedBucketUpdater;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Repository
public class ContestFinalizationBatchRepository {

    private static final int SUBMISSION_BATCH_SIZE = 500;
    private static final int ACCEPTED_BATCH_SIZE = 1000;
    private static final int USER_BATCH_SIZE = 100;

    private static final String INSERT_SUBMISSION_SQL =
            "INSERT INTO submission (user_id, problem_id, submitted_time, code, code_hash, result) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE result = VALUES(result), submitted_time = VALUES(submitted_time)";
    private static final String INSERT_ACCEPTED_SQL =
            "INSERT INTO accepted_submission (user_id, problem_id, submitted_time) VALUES (?, ?, ?)";
    private static final String UPDATE_SOLVED_COUNT_SQL =
            "UPDATE `user` SET solved_count = solved_count + ? WHERE id = ?";
    private static final String UPSERT_DAILY_ACTIVE_SQL =
            "INSERT INTO daily_active_users(day, user_id, last_active_time) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE last_active_time = VALUES(last_active_time)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SolvedBucketUpdater solvedBucketUpdater;

    public ContestFinalizationBatchRepository(JdbcTemplate jdbcTemplate,
                                              PlatformTransactionManager transactionManager,
                                              SolvedBucketUpdater solvedBucketUpdater) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.solvedBucketUpdater = solvedBucketUpdater;
    }

    public void insertSubmissions(List<SubmissionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (int start = 0; start < rows.size(); start += SUBMISSION_BATCH_SIZE) {
            int end = Math.min(start + SUBMISSION_BATCH_SIZE, rows.size());
            List<SubmissionRow> chunk = rows.subList(start, end);
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(
                    INSERT_SUBMISSION_SQL,
                    chunk,
                    SUBMISSION_BATCH_SIZE,
                    (ps, row) -> {
                        ps.setLong(1, row.userId());
                        ps.setLong(2, row.problemId());
                        LocalDateTime submittedTime = row.submittedTime();
                        ps.setTimestamp(3, submittedTime != null ? Timestamp.valueOf(submittedTime) : null);
                        ps.setString(4, row.code());
                        ps.setString(5, row.codeHash());
                        ps.setString(6, row.result().name());
                    }
            ));
        }
    }

    public void insertAcceptedSubmissions(List<AcceptedSubmission> accepted) {
        if (accepted == null || accepted.isEmpty()) {
            return;
        }
        for (int start = 0; start < accepted.size(); start += ACCEPTED_BATCH_SIZE) {
            int end = Math.min(start + ACCEPTED_BATCH_SIZE, accepted.size());
            List<AcceptedSubmission> chunk = accepted.subList(start, end);
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(
                    INSERT_ACCEPTED_SQL,
                    chunk,
                    ACCEPTED_BATCH_SIZE,
                    (ps, submission) -> {
                        ps.setLong(1, submission.getUser().getId());
                        ps.setLong(2, submission.getProblem().getId());
                        LocalDateTime submittedTime = submission.getSubmittedTime();
                        ps.setTimestamp(3, submittedTime != null ? Timestamp.valueOf(submittedTime) : null);
                    }
            ));
        }
    }

    public void incrementSolvedCountsAndDailyActivity(Map<Long, UserSolvedDelta> solvedChanges,
                                                      LocalDateTime effectiveTime) {
        if (solvedChanges == null || solvedChanges.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, UserSolvedDelta>> entries = solvedChanges.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().delta() > 0)
                .sorted(Comparator.comparingLong(e -> e.getValue().oldSolved()))
                .toList();
        if (entries.isEmpty()) {
            return;
        }
        Timestamp timestamp = Timestamp.valueOf(effectiveTime);
        Date date = Date.valueOf(effectiveTime.toLocalDate());
        for (int start = 0; start < entries.size(); start += USER_BATCH_SIZE) {
            int end = Math.min(start + USER_BATCH_SIZE, entries.size());
            List<Map.Entry<Long, UserSolvedDelta>> chunk = entries.subList(start, end);
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate(
                        UPDATE_SOLVED_COUNT_SQL,
                        chunk,
                        USER_BATCH_SIZE,
                        (ps, entry) -> {
                            ps.setLong(1, entry.getValue().delta());
                            ps.setLong(2, entry.getKey());
                        }
                );

                for (Map.Entry<Long, UserSolvedDelta> entry : chunk) {
                    long oldSolved = entry.getValue().oldSolved();
                    long delta = entry.getValue().delta();
                    for (int i = 0; i < delta; i++) {
                        solvedBucketUpdater.incrementFrom(oldSolved + i);
                    }
                }

                jdbcTemplate.batchUpdate(
                        UPSERT_DAILY_ACTIVE_SQL,
                        chunk,
                        USER_BATCH_SIZE,
                        (ps, entry) -> {
                            ps.setDate(1, date);
                            ps.setLong(2, entry.getKey());
                            ps.setTimestamp(3, timestamp);
                        }
                );
            });
        }
    }

    public record SubmissionRow(long userId,
                                 long problemId,
                                 LocalDateTime submittedTime,
                                 String code,
                                 String codeHash,
                                 SubmissionResult result) {
    }

    public record UserSolvedDelta(long oldSolved, long delta) {}
}
