package my.oj.web.contest.submission.queue;

import my.oj.web.contest.submission.core.ContestSubmission;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class JdbcContestSubmissionBatchPersistence implements ContestSubmissionBatchPersistence {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO contest_submission
                (id, contest_id, problem_id, user_id, submitted_time, code, code_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_BY_RESERVED_ID_SQL = """
            SELECT id, contest_id, problem_id, user_id, code_hash
            FROM contest_submission
            WHERE id IN (%s)
            """;
    private static final String SELECT_BY_DEDUP_KEY_SQL = """
            SELECT id, contest_id, problem_id, user_id, code_hash
            FROM contest_submission
            WHERE %s
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcContestSubmissionBatchPersistence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ContestSubmissionBatchInsertResult insertAll(List<ContestSubmission> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return new ContestSubmissionBatchInsertResult(Map.of());
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ContestSubmission submission = submissions.get(index);
                statement.setLong(1, submission.getId());
                statement.setLong(2, submission.getContest().getId());
                statement.setLong(3, submission.getProblem().getId());
                statement.setLong(4, submission.getUser().getId());
                statement.setTimestamp(5, Timestamp.valueOf(submission.getSubmittedTime()));
                statement.setString(6, submission.getCode());
                statement.setString(7, submission.getCodeHash());
            }

            @Override
            public int getBatchSize() {
                return submissions.size();
            }
        });

        List<StoredSubmissionRow> rowsByReservedId = selectByReservedIds(submissions);
        List<ContestSubmission> unresolved = unresolvedSubmissions(submissions, rowsByReservedId);
        List<StoredSubmissionRow> rowsByDedupKey = unresolved.isEmpty()
                ? List.of()
                : selectByDedupKeys(unresolved);
        return classify(submissions, rowsByReservedId, rowsByDedupKey);
    }

    private List<StoredSubmissionRow> selectByReservedIds(List<ContestSubmission> submissions) {
        String placeholders = String.join(",", Collections.nCopies(submissions.size(), "?"));
        Object[] ids = submissions.stream().map(ContestSubmission::getId).toArray();
        return jdbcTemplate.query(
                SELECT_BY_RESERVED_ID_SQL.formatted(placeholders),
                JdbcContestSubmissionBatchPersistence::mapStoredSubmission,
                ids
        );
    }

    private List<StoredSubmissionRow> selectByDedupKeys(List<ContestSubmission> submissions) {
        String predicates = String.join(
                " OR ",
                Collections.nCopies(
                        submissions.size(),
                        "(contest_id = ? AND problem_id = ? AND user_id = ? AND code_hash = ?)"
                )
        );
        List<Object> arguments = new ArrayList<>(submissions.size() * 4);
        for (ContestSubmission submission : submissions) {
            arguments.add(submission.getContest().getId());
            arguments.add(submission.getProblem().getId());
            arguments.add(submission.getUser().getId());
            arguments.add(submission.getCodeHash());
        }
        return jdbcTemplate.query(
                SELECT_BY_DEDUP_KEY_SQL.formatted(predicates),
                JdbcContestSubmissionBatchPersistence::mapStoredSubmission,
                arguments.toArray()
        );
    }

    private static StoredSubmissionRow mapStoredSubmission(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredSubmissionRow(
                resultSet.getLong("id"),
                resultSet.getLong("contest_id"),
                resultSet.getLong("problem_id"),
                resultSet.getLong("user_id"),
                resultSet.getString("code_hash")
        );
    }

    static ContestSubmissionBatchInsertResult classify(List<ContestSubmission> submissions,
                                                       List<StoredSubmissionRow> rowsByReservedId,
                                                       List<StoredSubmissionRow> rowsByDedupKey) {
        Map<Long, ContestSubmission> submissionsByReservedId = new LinkedHashMap<>();
        for (ContestSubmission submission : submissions) {
            ContestSubmission previous = submissionsByReservedId.put(submission.getId(), submission);
            if (previous != null) {
                throw new ContestSubmissionBatchConsistencyException(
                        "Duplicate reserved contest submission id in batch: " + submission.getId()
                );
            }
        }

        Map<Long, StoredSubmissionRow> storedById = new LinkedHashMap<>();
        for (StoredSubmissionRow row : rowsByReservedId) {
            storedById.put(row.id(), row);
        }
        Map<DedupKey, StoredSubmissionRow> storedByKey = new LinkedHashMap<>();
        for (StoredSubmissionRow row : rowsByDedupKey) {
            storedByKey.put(row.dedupKey(), row);
        }

        Map<Long, ContestSubmissionBatchInsertResult.Resolution> resolutions = new LinkedHashMap<>();
        List<Long> occupiedReservedIds = new ArrayList<>();
        List<Long> unexplainedMissingIds = new ArrayList<>();
        for (ContestSubmission submission : submissions) {
            StoredSubmissionRow storedAtReservedId = storedById.get(submission.getId());
            if (storedAtReservedId != null) {
                if (!storedAtReservedId.matches(submission)) {
                    occupiedReservedIds.add(submission.getId());
                    continue;
                }
                resolutions.put(
                        submission.getId(),
                        ContestSubmissionBatchInsertResult.Resolution.inserted(submission.getId())
                );
                continue;
            }

            StoredSubmissionRow duplicate = storedByKey.get(DedupKey.from(submission));
            if (duplicate != null) {
                resolutions.put(
                        submission.getId(),
                        ContestSubmissionBatchInsertResult.Resolution.duplicate(duplicate.id())
                );
                continue;
            }

            unexplainedMissingIds.add(submission.getId());
        }

        // Both branches are collected rather than thrown on sight so the writer learns every bad
        // row from one attempt and only has to retry the remainder once.
        if (!occupiedReservedIds.isEmpty() || !unexplainedMissingIds.isEmpty()) {
            List<Long> offendingIds = new ArrayList<>(occupiedReservedIds);
            offendingIds.addAll(unexplainedMissingIds);
            throw new ContestSubmissionBatchConsistencyException(
                    describeConsistencyFailure(occupiedReservedIds, unexplainedMissingIds),
                    offendingIds
            );
        }
        return new ContestSubmissionBatchInsertResult(resolutions);
    }

    private static String describeConsistencyFailure(List<Long> occupiedReservedIds,
                                                     List<Long> unexplainedMissingIds) {
        StringBuilder message = new StringBuilder();
        if (!occupiedReservedIds.isEmpty()) {
            message.append("Reserved contest submission ids occupied by a different submission: ")
                    .append(occupiedReservedIds);
        }
        if (!unexplainedMissingIds.isEmpty()) {
            if (!message.isEmpty()) {
                message.append("; ");
            }
            message.append("INSERT IGNORE omitted contest submissions without a matching duplicate: ")
                    .append(unexplainedMissingIds);
        }
        return message.toString();
    }

    private static List<ContestSubmission> unresolvedSubmissions(List<ContestSubmission> submissions,
                                                                 List<StoredSubmissionRow> rowsByReservedId) {
        Map<Long, StoredSubmissionRow> storedById = new LinkedHashMap<>();
        for (StoredSubmissionRow row : rowsByReservedId) {
            storedById.put(row.id(), row);
        }
        return submissions.stream()
                .filter(submission -> !storedById.containsKey(submission.getId()))
                .toList();
    }

    static record StoredSubmissionRow(
            long id,
            long contestId,
            long problemId,
            long userId,
            String codeHash
    ) {
        private DedupKey dedupKey() {
            return new DedupKey(contestId, problemId, userId, codeHash);
        }

        private boolean matches(ContestSubmission submission) {
            return contestId == submission.getContest().getId()
                    && problemId == submission.getProblem().getId()
                    && userId == submission.getUser().getId()
                    && Objects.equals(codeHash, submission.getCodeHash());
        }
    }

    private record DedupKey(long contestId, long problemId, long userId, String codeHash) {
        private static DedupKey from(ContestSubmission submission) {
            return new DedupKey(
                    submission.getContest().getId(),
                    submission.getProblem().getId(),
                    submission.getUser().getId(),
                    submission.getCodeHash()
            );
        }
    }
}
