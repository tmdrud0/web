package my.oj.web.submission.event.normal;

import my.oj.web.problem.Problem;
import my.oj.web.submission.Submission;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.accepted.AcceptedSubmission;
import my.oj.web.submission.accepted.AcceptedSubmissionRepository;
import my.oj.web.submission.event.guard.UserGuardRepository;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import my.oj.web.user.activity.DailyActiveUserRepository;
import my.oj.web.user.rank.solved.SolvedBucketUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NormalSubmissionResultServiceTests {

    @Mock
    private SubmissionRepository submissionRepository;
    @Mock
    private AcceptedSubmissionRepository acceptedSubmissionRepository;
    @Mock
    private UserGuardRepository userGuardRepository;
    @Mock
    private DailyActiveUserRepository dailyActiveUserRepository;
    @Mock
    private SolvedBucketUpdater solvedBucketUpdater;

    private NormalSubmissionResultService service;

    private User user;
    private Submission submission;
    private LocalDateTime submittedTime;

    @BeforeEach
    void setUp() {
        service = new NormalSubmissionResultService(
                submissionRepository,
                acceptedSubmissionRepository,
                userGuardRepository,
                dailyActiveUserRepository,
                solvedBucketUpdater
        );

        user = User.withState(10L, "alice", "pw", 5L, new Streak());
        Problem problem = Problem.create("Two Sum", null, null);
        ReflectionTestUtils.setField(problem, "id", 200L);
        submittedTime = LocalDateTime.now();
        submission = Submission.create(user, problem, "print(1)", submittedTime);
        ReflectionTestUtils.setField(submission, "id", 300L);

        given(submissionRepository.getReferenceById(300L)).willReturn(submission);
    }

    @Test
    void handleSubmissionResult_acceptLocksUserBeforeRecording() {
        service.handleSubmissionResult(300L, SubmissionResult.ACCEPTED, submittedTime.plusSeconds(5));

        InOrder inOrder = inOrder(acceptedSubmissionRepository, userGuardRepository);
        inOrder.verify(acceptedSubmissionRepository).save(any(AcceptedSubmission.class));
        inOrder.verify(userGuardRepository).guard(10L);

        verify(solvedBucketUpdater).incrementFrom(5L);
        verify(dailyActiveUserRepository).upsert(
                eq(submittedTime.toLocalDate()),
                eq(10L),
                eq(submittedTime)
        );
        assertThat(user.getSolvedCount()).isEqualTo(6L);
        assertThat(user.getStreak().getLastSolvedDate()).isEqualTo(submittedTime);
    }

    @Test
    void handleSubmissionResult_skipsWhenAlreadyAccepted() {
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(acceptedSubmissionRepository).save(any(AcceptedSubmission.class));

        service.handleSubmissionResult(300L, SubmissionResult.ACCEPTED, submittedTime.plusSeconds(5));

        verify(userGuardRepository, never()).guard(any(Long.class));

        verify(solvedBucketUpdater, never()).incrementFrom(any(Long.class));
        verify(dailyActiveUserRepository, never()).upsert(any(LocalDate.class), any(Long.class), any(LocalDateTime.class));
        assertThat(user.getSolvedCount()).isEqualTo(5L);
    }
}
