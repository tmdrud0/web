package my.oj.web.submission;

import my.oj.web.config.TestQuerydslConfig;
import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.dto.SubmissionSummaryDto;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Slice;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestQuerydslConfig.class)
class SubmissionRepositoryImplTests {

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProblemRepository problemRepository;

    private User user;
    private Problem problem;
    private Submission acceptedSubmission;
    private Submission wrongSubmission;
    private Submission pendingSubmission;

    @BeforeEach
    void setUp() {
        submissionRepository.deleteAll();
        problemRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(User.withState(null, "alice", "pw", 0L, new Streak()));
        problem = problemRepository.save(Problem.create("Two Sum", null, null));

        acceptedSubmission = Submission.create(user, problem, "print(1)", LocalDateTime.now().minusMinutes(3));
        acceptedSubmission.setResult(SubmissionResult.ACCEPTED);

        wrongSubmission = Submission.create(user, problem, "print(2)", LocalDateTime.now().minusMinutes(2));
        wrongSubmission.setResult(SubmissionResult.WRONG_ANSWER);

        pendingSubmission = Submission.create(user, problem, "print(3)", LocalDateTime.now().minusMinutes(1));
        pendingSubmission.setResult(SubmissionResult.PENDING);

        submissionRepository.saveAll(List.of(acceptedSubmission, wrongSubmission, pendingSubmission));
    }

    @Test
    void findSummaries_supportsAscendingOrder() {
        SliceResult firstSlice = fetch(null, 2, SubmissionSortOrder.ASC, false);

        assertThat(firstSlice.slice.getContent())
                .extracting(SubmissionSummaryDto::getId)
                .containsExactly(acceptedSubmission.getId(), wrongSubmission.getId());
        assertThat(firstSlice.slice.hasNext()).isTrue();

        SliceResult secondSlice = fetch(firstSlice.lastId(), 2, SubmissionSortOrder.ASC, false);

        assertThat(secondSlice.slice.getContent())
                .extracting(SubmissionSummaryDto::getId)
                .containsExactly(pendingSubmission.getId());
        assertThat(secondSlice.slice.hasNext()).isFalse();
    }

    @Test
    void findSummaries_filtersAcceptedOnly() {
        SliceResult slice = fetch(null, 10, SubmissionSortOrder.DESC, true);

        assertThat(slice.slice.getContent())
                .extracting(SubmissionSummaryDto::getResult)
                .containsOnly(SubmissionResult.ACCEPTED);
        assertThat(slice.slice.getContent())
                .extracting(SubmissionSummaryDto::getId)
                .containsExactly(acceptedSubmission.getId());
    }

    private SliceResult fetch(Long lastId, int size, SubmissionSortOrder order, boolean acceptedOnly) {
        var slice = submissionRepository.findSummaries(
                user.getName(),
                problem.getId(),
                lastId,
                size,
                order,
                acceptedOnly
        );
        Long newLastId = slice.hasContent()
                ? slice.getContent().get(slice.getNumberOfElements() - 1).getId()
                : null;
        return new SliceResult(slice, newLastId);
    }

    private record SliceResult(Slice<SubmissionSummaryDto> slice,
                               Long lastId) {
    }
}
