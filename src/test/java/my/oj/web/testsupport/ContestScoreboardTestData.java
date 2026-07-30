package my.oj.web.testsupport;

import my.oj.web.submission.SubmissionResult;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Seeds one contest's submissions and scoreboard outbox rows, for tests that drain the outbox
 * into Redis and then read the scoreboard back.
 */
public final class ContestScoreboardTestData {

    private ContestScoreboardTestData() {
    }

    /**
     * One submission and the judgement it eventually got. {@code judgedMinute} is what drives
     * the outbox order, so a late judgement on an early submission reproduces the case the live
     * scoreboard and a rebuild used to disagree on.
     */
    public record Attempt(long submissionId,
                          long problemId,
                          long userId,
                          int submittedMinute,
                          int judgedMinute,
                          SubmissionResult result) {
    }

    public record SeededContest(long contestId, List<Long> problemIds, List<Long> userIds) {
    }

    public static SeededContest seedContest(JdbcTemplate jdbcTemplate,
                                            String namePrefix,
                                            LocalDateTime contestStart,
                                            int problemCount,
                                            int userCount) {
        String name = namePrefix + "-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contest (name, start_time, end_time) VALUES (?, ?, ?)",
                name,
                contestStart,
                contestStart.plusHours(3)
        );
        long contestId = jdbcTemplate.queryForObject("SELECT id FROM contest WHERE name = ?", Long.class, name);

        List<Long> problemIds = new ArrayList<>(problemCount);
        for (int index = 1; index <= problemCount; index++) {
            String problemName = name + "-problem-" + index;
            jdbcTemplate.update(
                    "INSERT INTO problem (name, contest_id, contest_num) VALUES (?, ?, ?)",
                    problemName,
                    contestId,
                    index
            );
            problemIds.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM problem WHERE name = ?", Long.class, problemName));
        }

        List<Long> userIds = new ArrayList<>(userCount);
        for (int index = 1; index <= userCount; index++) {
            String userName = name + "-user-" + index;
            jdbcTemplate.update("INSERT INTO `user` (name, pass) VALUES (?, ?)", userName, "pass");
            userIds.add(jdbcTemplate.queryForObject(
                    "SELECT id FROM `user` WHERE name = ?", Long.class, userName));
        }
        return new SeededContest(contestId, problemIds, userIds);
    }

    /**
     * Writes each attempt as a submission plus a PENDING outbox row, using the judgement time as
     * {@code created_at} so the outbox drains in judging order.
     *
     * @param withResultRows also write {@code contest_submission_result}, which a rebuild reads
     */
    public static void insertAttempts(JdbcTemplate jdbcTemplate,
                                      long contestId,
                                      LocalDateTime contestStart,
                                      List<Attempt> attempts,
                                      boolean withResultRows) {
        List<Object[]> submissions = new ArrayList<>(attempts.size());
        List<Object[]> results = new ArrayList<>(attempts.size());
        List<Object[]> outboxes = new ArrayList<>(attempts.size());
        for (Attempt attempt : attempts) {
            LocalDateTime submittedAt = contestStart.plusMinutes(attempt.submittedMinute());
            LocalDateTime judgedAt = contestStart.plusMinutes(attempt.judgedMinute());
            submissions.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.problemId(), attempt.userId(),
                    submittedAt, "code", String.format(Locale.ROOT, "%064x", attempt.submissionId())
            });
            results.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.result().name(), judgedAt,
                    attempt.result().name(), judgedAt
            });
            outboxes.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.problemId(), attempt.userId(),
                    contestStart, submittedAt, judgedAt, attempt.result().name(), judgedAt, judgedAt
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission (
                    id, contest_id, problem_id, user_id, submitted_time, code, code_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, submissions);
        if (withResultRows) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO contest_submission_result (
                        submission_id, contest_id, provisional_result, provisional_judged_at,
                        final_result, final_judged_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, results);
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission_outbox (
                    contest_submission_id, contest_id, problem_id, user_id,
                    contest_start, submitted_time, judged_at, result, status, created_at, due_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, outboxes);
    }

    public static void deleteContest(JdbcTemplate jdbcTemplate, long contestId) {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM contest_submission WHERE contest_id = ?",
                Long.class,
                contestId
        );
        jdbcTemplate.update("DELETE FROM contest_submission_outbox WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest_submission_result WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest_submission WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM problem WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest WHERE id = ?", contestId);
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
    }

    /** Outbox rows this contest still has to apply. */
    public static long unappliedEvents(JdbcTemplate jdbcTemplate, long contestId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM contest_submission_outbox
                WHERE contest_id = ? AND status <> 'COMPLETED'
                """, Long.class, contestId);
        return count == null ? 0L : count;
    }

    public static long seededEvents(JdbcTemplate jdbcTemplate, long contestId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission_outbox WHERE contest_id = ?",
                Long.class,
                contestId
        );
        return count == null ? 0L : count;
    }

    /** The set the scoreboard marks each applied event in. */
    public static String processedKey(long contestId) {
        return "contest:scoreboard:" + contestId + ":processed";
    }

    public static void flushRedis(StringRedisTemplate redisTemplate) {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
