package my.oj.web.submission;

import my.oj.web.problem.Problem;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.dto.SubmissionReceipt;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import my.oj.web.submission.event.SubmissionSubmittedEvent;
import my.oj.web.submission.store.SubmissionStoreResult;
import my.oj.web.submission.store.SubmissionStoreStrategySelector;
import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTests {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProblemRepository problemRepository;

    @Mock
    private SubmissionStoreStrategySelector storeSelector;

    @Mock
    private ApplicationEventPublisher publisher;

    @InjectMocks
    private SubmissionService submissionService;

    private User user;
    private Problem problem;

    @BeforeEach
    void setUp() {
        user = User.withState(5L, "bob", "pw", 0L, new Streak());
        problem = Problem.create("Prob", null, null);
        ReflectionTestUtils.setField(problem, "id", 9L);
        when(userRepository.getReferenceById(5L)).thenReturn(user);
        when(problemRepository.getReferenceById(9L)).thenReturn(problem);
    }

    @Test
    void submit_publishesEvent_whenNewSubmission() {
        when(storeSelector.store(any(Submission.class)))
                .thenReturn(new SubmissionStoreResult(101L, SubmissionOrigin.NORMAL, false));

        SubmissionReceipt receipt = submissionService.submit(new SubmitSubmissionCommand(5L, 9L, "code"));

        assertThat(receipt.submissionId()).isEqualTo(101L);
        assertThat(receipt.isDuplicate()).isFalse();

        ArgumentCaptor<SubmissionSubmittedEvent> eventCaptor = ArgumentCaptor.forClass(SubmissionSubmittedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().submissionId()).isEqualTo(101L);
        assertThat(eventCaptor.getValue().origin()).isEqualTo(SubmissionOrigin.NORMAL);
    }

    @Test
    void submit_skipsEvent_whenDuplicate() {
        when(storeSelector.store(any(Submission.class)))
                .thenReturn(new SubmissionStoreResult(202L, SubmissionOrigin.NORMAL, true));

        SubmissionReceipt receipt = submissionService.submit(new SubmitSubmissionCommand(5L, 9L, "code"));

        assertThat(receipt.isDuplicate()).isTrue();
        verify(publisher, never()).publishEvent(any());
    }
}

