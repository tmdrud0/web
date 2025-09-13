package my.oj.web.submission.store;

import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NormalSubmissionStoreStrategyTests {

    @Mock
    private SubmissionRepository submissionRepository;

    @InjectMocks
    private NormalSubmissionStoreStrategy strategy;

    private Submission submission;
    private Submission existing;

    @BeforeEach
    void setUp() {
        User user = User.withState(10L, "alice", "pw", 0L, new Streak());
        Problem problem = Problem.create("P", null, null);
        ReflectionTestUtils.setField(problem, "id", 77L);
        submission = Submission.create(user, problem, "print(1)", LocalDateTime.now());
        existing = Submission.create(user, problem, "print(1)", LocalDateTime.now().minusMinutes(5));
        existing.setResult(SubmissionResult.ACCEPTED);
        ReflectionTestUtils.setField(existing, "id", 55L);
    }

    @Test
    void save_returnsDuplicateRecordWhenCodeExists() {
        given(submissionRepository.findFirstByUserIdAndProblemIdAndCodeHash(eq(submission.getUser().getId()),
                eq(submission.getProblem().getId()), eq(submission.getCodeHash())))
                .willReturn(Optional.of(existing));

        SubmissionStoreResult result = strategy.save(submission);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.submissionId()).isEqualTo(55L);
        verify(submissionRepository).findFirstByUserIdAndProblemIdAndCodeHash(anyLong(), anyLong(), any());
    }

    @Test
    void save_persistsWhenNoDuplicate() {
        given(submissionRepository.findFirstByUserIdAndProblemIdAndCodeHash(eq(submission.getUser().getId()),
                eq(submission.getProblem().getId()), eq(submission.getCodeHash())))
                .willReturn(Optional.empty());
        given(submissionRepository.save(any(Submission.class)))
                .willAnswer(invocation -> {
                    Submission saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 99L);
                    return saved;
                });

        SubmissionStoreResult result = strategy.save(submission);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.submissionId()).isEqualTo(99L);
        verify(submissionRepository).save(any(Submission.class));
    }
}
