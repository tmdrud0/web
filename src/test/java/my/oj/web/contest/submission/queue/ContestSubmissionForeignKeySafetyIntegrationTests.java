package my.oj.web.contest.submission.queue;

import my.oj.web.contest.Contest;
import my.oj.web.contest.ContestRepository;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.SubmissionService;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "contest.submission.bulk.batch-size=1",
        "contest.submission.bulk.worker-count=1",
        "contest.submission.bulk.flush-interval-millis=50",
        "contest.outbox.scheduler.enabled=false",
        "contest.submission.rate-limit.store=none",
        "contest.submission.dedup.store=memory"
})
class ContestSubmissionForeignKeySafetyIntegrationTests {

    @Autowired
    private SubmissionService submissionService;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contestId;
    private Long problemId;

    @AfterEach
    void tearDown() {
        if (problemId != null) {
            jdbcTemplate.update("DELETE FROM contest_submission WHERE problem_id = ?", problemId);
            jdbcTemplate.update("DELETE FROM problem WHERE id = ?", problemId);
        }
        if (contestId != null) {
            jdbcTemplate.update("DELETE FROM contest WHERE id = ?", contestId);
        }
    }

    @Test
    void nonexistentUserIsNotSilentlyLostByInsertIgnore() {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = new Contest("fk-safety");
        ReflectionTestUtils.setField(contest, "startTime", now.minusMinutes(1));
        ReflectionTestUtils.setField(contest, "endTime", now.plusMinutes(10));
        Contest savedContest = contestRepository.save(contest);
        contestId = savedContest.getId();

        Problem savedProblem = problemRepository.save(Problem.create("A", savedContest, 1L));
        problemId = savedProblem.getId();
        Long nonexistentUserId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1000000 FROM `user`",
                Long.class
        );

        assertThatThrownBy(() -> submissionService.submit(new SubmitSubmissionCommand(
                nonexistentUserId,
                problemId,
                "print('missing-user')"
        )))
                .hasRootCauseInstanceOf(ContestSubmissionBatchConsistencyException.class);

        Integer persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest_submission WHERE problem_id = ? AND user_id = ?",
                Integer.class,
                problemId,
                nonexistentUserId
        );
        assertThat(persisted).isZero();
    }
}
