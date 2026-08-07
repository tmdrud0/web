package my.oj.web.perf;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.submission.queue.ContestSubmissionBulkMetrics;
import my.oj.web.perf.dto.ContestSeedRequest;
import my.oj.web.perf.dto.ContestSeedResult;
import my.oj.web.perf.dto.ContestSubmissionBulkStatsResult;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("perf")
@RequiredArgsConstructor
public class ContestPerfService {

    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final ContestSubmissionBulkMetrics contestSubmissionBulkMetrics;

    @Transactional
    public ContestSeedResult seedContest(ContestSeedRequest request) {
        String prefix = request.resolvedPrefix();
        if (request.shouldReset()) {
            cleanup(prefix);
        }

        LocalDateTime start = request.resolvedStartTime();
        LocalDateTime end = start.plusMinutes(request.resolvedDurationMinutes());

        long contestId = insertContest(prefix, start, end);
        IdRange problemRange = insertProblems(prefix, contestId, request.resolvedProblemCount());
        IdRange userRange = insertUsers(prefix, request.resolvedUserCount(), start.minusDays(1));

        return new ContestSeedResult(
                contestId,
                request.resolvedProblemCount(),
                problemRange.first,
                problemRange.last,
                request.resolvedUserCount(),
                userRange.first,
                userRange.last
        );
    }

    @Transactional
    public ContestSeedResult seedPractice(ContestSeedRequest request) {
        LocalDateTime now = LocalDateTime.now();
        ContestSeedRequest practiceRequest = new ContestSeedRequest(
                request.resolvedPrefix(),
                request.resolvedUserCount(),
                request.resolvedProblemCount(),
                request.resolvedDurationMinutes(),
                now.minusMinutes(request.resolvedDurationMinutes() + 10L),
                request.shouldReset()
        );
        return seedContest(practiceRequest);
    }

    public ContestSubmissionBulkStatsResult getBulkStats() {
        ContestSubmissionBulkMetrics.Snapshot snapshot = contestSubmissionBulkMetrics.snapshot();
        return new ContestSubmissionBulkStatsResult(
                snapshot.chunkCount(),
                snapshot.failedChunkCount(),
                snapshot.totalSubmissionCount(),
                snapshot.averageChunkElapsedMillis(),
                snapshot.maxChunkElapsedMillis(),
                snapshot.maxChunkSize(),
                snapshot.maxPendingBefore(),
                snapshot.maxPendingAfter(),
                snapshot.lastPendingAfter(),
                snapshot.maxActiveWorkers(),
                snapshot.completionTaskCount(),
                snapshot.failedCompletionTaskCount(),
                snapshot.completionSubmissionCount(),
                snapshot.averageCompletionQueueDelayMillis(),
                snapshot.maxCompletionQueueDelayMillis(),
                snapshot.averageCompletionElapsedMillis(),
                snapshot.maxCompletionElapsedMillis(),
                snapshot.maxCompletionQueueDepth(),
                snapshot.maxActiveCompletionWorkers(),
                snapshot.completionCallerRunsCount(),
                snapshot.rejectedSubmissionCount(),
                snapshot.currentInFlight(),
                snapshot.maxInFlight()
        );
    }

    public ContestSubmissionBulkStatsResult resetBulkStats() {
        contestSubmissionBulkMetrics.reset();
        return getBulkStats();
    }

    private long insertContest(String prefix, LocalDateTime start, LocalDateTime end) {
        String name = contestName(prefix);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO contest (name, start_time, end_time) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, name);
            ps.setTimestamp(2, Timestamp.valueOf(start));
            ps.setTimestamp(3, Timestamp.valueOf(end));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create contest");
        }
        return key.longValue();
    }

    private IdRange insertProblems(String prefix, long contestId, int problemCount) {
        if (problemCount <= 0) {
            return new IdRange(0, 0);
        }

        String sql = "INSERT INTO problem (name, contest_id, contest_num) VALUES (?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                long contestNum = i + 1L;
                ps.setString(1, problemName(prefix, contestNum));
                ps.setLong(2, contestId);
                ps.setLong(3, contestNum);
            }

            @Override
            public int getBatchSize() {
                return problemCount;
            }
        });

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM problem WHERE contest_id = ? ORDER BY id",
                Long.class,
                contestId
        );
        if (ids.isEmpty()) {
            throw new IllegalStateException("Problems were not created");
        }
        return new IdRange(ids.get(0), ids.get(ids.size() - 1));
    }

    private IdRange insertUsers(String prefix, int userCount, LocalDateTime lastSolved) {
        if (userCount <= 0) {
            return new IdRange(0, 0);
        }

        String sql = """
                INSERT INTO `user` (name, pass, solved_count, streak_last_solved_date, streak_current_streak, streak_longest_streak)
                VALUES (?, 'pass', 0, ?, 0, 0)
                """;

        for (int offset = 0; offset < userCount; offset += BATCH_SIZE) {
            int batchSize = Math.min(BATCH_SIZE, userCount - offset);
            int base = offset;
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                    int index = base + i + 1;
                    ps.setString(1, userName(prefix, index));
                    ps.setTimestamp(2, Timestamp.valueOf(lastSolved));
                }

                @Override
                public int getBatchSize() {
                    return batchSize;
                }
            });
        }

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM `user` WHERE name LIKE ? ORDER BY id",
                Long.class,
                prefix + "_user_%"
        );

        if (ids.isEmpty()) {
            throw new IllegalStateException("Users were not created");
        }
        return new IdRange(ids.get(0), ids.get(ids.size() - 1));
    }

    private void cleanup(String prefix) {
        List<Long> contestIds = jdbcTemplate.queryForList(
                "SELECT id FROM contest WHERE name = ?",
                Long.class,
                contestName(prefix)
        );

        for (Long contestId : contestIds) {
            jdbcTemplate.update("DELETE FROM contest_submission_outbox WHERE contest_id = ?", contestId);
            jdbcTemplate.update("DELETE FROM contest_final_score WHERE contest_id = ?", contestId);
            jdbcTemplate.update("DELETE FROM contest_submission_result WHERE contest_id = ?", contestId);
            jdbcTemplate.update("DELETE FROM contest_submission WHERE contest_id = ?", contestId);
        }

        jdbcTemplate.update("DELETE FROM problem WHERE name LIKE ?", prefix + "_problem_%");
        jdbcTemplate.update("DELETE FROM contest WHERE name = ?", contestName(prefix));
        jdbcTemplate.update("DELETE FROM `user` WHERE name LIKE ?", prefix + "_user_%");
    }

    private static String contestName(String prefix) {
        return prefix + "_contest";
    }

    private static String problemName(String prefix, long contestNum) {
        return prefix + "_problem_" + contestNum;
    }

    private static String userName(String prefix, int index) {
        return prefix + "_user_" + index;
    }

    private record IdRange(long first, long last) {
    }
}
