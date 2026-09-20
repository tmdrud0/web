package my.oj.web.testsupport;

import my.oj.web.submission.SubmissionResult;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Seeds one contest's stored judgement results for live-versus-rebuild scoreboard tests.
 */
public final class ContestScoreboardTestData {

    private ContestScoreboardTestData() {
    }

    /**
     * One submission and the judgement it eventually got. {@code judgedMinute} is what drives
     * stream order, so a late judgement on an early submission reproduces the case the live
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
     * Writes each attempt as a submission and optionally its stored judgement result.
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
    }

    /**
     * The eight attempts the scoreboard comparisons share: a late judgement on an early submission,
     * two accepted attempts on the same problem, and a wrong attempt before an acceptance, spread
     * over two problems and two users.
     *
     * <p>Both the live-versus-rebuild check and the full-replay checks pin the same expected
     * ranking for these judgements - user 0 solves 2 with 44 penalty minutes, user 1 solves 1 with
     * 5 - so the fixture lives in one place rather than drifting between the two.</p>
     *
     * @param contest the seeded contest, whose first two problems and users these attempts use
     */
    public static List<Attempt> standardAttempts(SeededContest contest, LocalDateTime contestStart) {
        long problemA = contest.problemIds().get(0);
        long problemB = contest.problemIds().get(1);
        long userA = contest.userIds().get(0);
        long userB = contest.userIds().get(1);

        return List.of(
                new Attempt(920_000_000_000_000_001L, problemA, userA, 10, 14, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_002L, problemA, userA, 12, 12, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_003L, problemB, userA, 20, 21, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_004L, problemB, userA, 22, 30, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_005L, problemB, userA, 25, 26, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_006L, problemA, userB, 5, 40, SubmissionResult.ACCEPTED),
                new Attempt(920_000_000_000_000_007L, problemA, userB, 7, 8, SubmissionResult.WRONG_ANSWER),
                new Attempt(920_000_000_000_000_008L, problemB, userB, 9, 10, SubmissionResult.RUNTIME_ERROR)
        );
    }

    /** The order the broker would deliver {@link #standardAttempts} in. */
    public static List<Attempt> inJudgingOrder(List<Attempt> attempts) {
        return attempts.stream()
                .sorted(Comparator.comparingInt(Attempt::judgedMinute)
                        .thenComparingLong(Attempt::submissionId))
                .toList();
    }

    /**
     * Writes each attempt as a submission and its stored judgement, leaving {@code final_result}
     * null.
     *
     * <p>This is the shape MySQL actually holds while a contest is running:
     * {@code JdbcContestSubmissionJudgeResultBatchPersistence} inserts a provisional result and no
     * final one, and only finalization or a rejudge fills {@code final_result} in. A replay that
     * read {@code final_result} alone would therefore apply nothing at all here.</p>
     */
    public static void insertAttemptsWithProvisionalResultOnly(JdbcTemplate jdbcTemplate,
                                                              long contestId,
                                                              LocalDateTime contestStart,
                                                              List<Attempt> attempts) {
        List<Object[]> submissions = new ArrayList<>(attempts.size());
        List<Object[]> results = new ArrayList<>(attempts.size());
        for (Attempt attempt : attempts) {
            LocalDateTime submittedAt = contestStart.plusMinutes(attempt.submittedMinute());
            LocalDateTime judgedAt = contestStart.plusMinutes(attempt.judgedMinute());
            submissions.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.problemId(), attempt.userId(),
                    submittedAt, "code", String.format(Locale.ROOT, "%064x", attempt.submissionId())
            });
            results.add(new Object[]{
                    attempt.submissionId(), contestId, attempt.result().name(), judgedAt
            });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission (
                    id, contest_id, problem_id, user_id, submitted_time, code, code_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, submissions);
        jdbcTemplate.batchUpdate("""
                INSERT INTO contest_submission_result (
                    submission_id, contest_id, provisional_result, provisional_judged_at
                ) VALUES (?, ?, ?, ?)
                """, results);
    }

    public static void deleteContest(JdbcTemplate jdbcTemplate, long contestId) {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM contest_submission WHERE contest_id = ?",
                Long.class,
                contestId
        );
        jdbcTemplate.update("DELETE FROM contest_submission_result WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest_submission WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM problem WHERE contest_id = ?", contestId);
        jdbcTemplate.update("DELETE FROM contest WHERE id = ?", contestId);
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
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
