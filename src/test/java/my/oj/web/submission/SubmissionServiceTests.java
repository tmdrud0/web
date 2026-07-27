package my.oj.web.submission;

import my.oj.web.contest.Contest;
import my.oj.web.contest.submission.support.ContestSubmissionRateLimiter;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private ContestSubmissionRateLimiter contestSubmissionRateLimiter;

    @InjectMocks
    private SubmissionService submissionService;

    private User user;
    private Problem problem;

    @BeforeEach
    void setUp() {
        user = User.withState(5L, "bob", "pw", 0L, new Streak());
        problem = Problem.create("Prob", null, null);
        ReflectionTestUtils.setField(problem, "id", 9L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        lenient().when(problemRepository.findWithContestById(9L)).thenReturn(Optional.of(problem));
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
        verifyNoInteractions(contestSubmissionRateLimiter);
    }

    @Test
    void submit_skipsEvent_whenDuplicate() {
        when(storeSelector.store(any(Submission.class)))
                .thenReturn(new SubmissionStoreResult(202L, SubmissionOrigin.NORMAL, true));

        SubmissionReceipt receipt = submissionService.submit(new SubmitSubmissionCommand(5L, 9L, "code"));

        assertThat(receipt.isDuplicate()).isTrue();
        verify(publisher, never()).publishEvent(any());
        verifyNoInteractions(contestSubmissionRateLimiter);
    }

    @Test
    void submit_rejectsContestSubmission_whenRateLimited() {
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 77L);
        Problem contestProblem = Problem.create("Contest Prob", contest, 1L);
        ReflectionTestUtils.setField(contestProblem, "id", 10L);
        when(problemRepository.findWithContestById(10L)).thenReturn(Optional.of(contestProblem));
        when(storeSelector.onContest(eq(contestProblem), any(LocalDateTime.class))).thenReturn(true);
        when(contestSubmissionRateLimiter.tryAcquire(77L, 5L)).thenReturn(Optional.of(Duration.ofMillis(1400)));

        assertThatThrownBy(() -> submissionService.submit(new SubmitSubmissionCommand(5L, 10L, "code")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too frequent")
                .hasMessageContaining("2 seconds");

        verify(storeSelector, never()).store(any(Submission.class));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void submit_releasesRateLimit_whenContestStoreFails() {
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 88L);
        Problem contestProblem = Problem.create("Contest Prob", contest, 1L);
        ReflectionTestUtils.setField(contestProblem, "id", 11L);
        when(problemRepository.findWithContestById(11L)).thenReturn(Optional.of(contestProblem));
        when(storeSelector.onContest(eq(contestProblem), any(LocalDateTime.class))).thenReturn(true);
        when(contestSubmissionRateLimiter.tryAcquire(88L, 5L)).thenReturn(Optional.empty());
        when(storeSelector.store(any(Submission.class))).thenThrow(new IllegalStateException("db error"));

        assertThatThrownBy(() -> submissionService.submit(new SubmitSubmissionCommand(5L, 11L, "code")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db error");

        verify(contestSubmissionRateLimiter).release(88L, 5L);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void submitAsync_completesAfterContestStoreWithoutPublishingApplicationEvent() {
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 89L);
        Problem contestProblem = Problem.create("Contest Prob", contest, 1L);
        ReflectionTestUtils.setField(contestProblem, "id", 12L);
        when(problemRepository.findWithContestById(12L)).thenReturn(Optional.of(contestProblem));
        when(storeSelector.onContest(eq(contestProblem), any(LocalDateTime.class))).thenReturn(true);
        when(contestSubmissionRateLimiter.tryAcquire(89L, 5L)).thenReturn(Optional.empty());

        CompletableFuture<SubmissionStoreResult> storeFuture = new CompletableFuture<>();
        when(storeSelector.storeAsync(any(Submission.class))).thenReturn(storeFuture);

        CompletionStage<SubmissionReceipt> stage = submissionService.submitAsync(
                new SubmitSubmissionCommand(5L, 12L, "code")
        );

        assertThat(stage.toCompletableFuture()).isNotDone();
        verify(publisher, never()).publishEvent(any());

        storeFuture.complete(new SubmissionStoreResult(303L, SubmissionOrigin.CONTEST, false));

        SubmissionReceipt receipt = stage.toCompletableFuture().join();
        assertThat(receipt.submissionId()).isEqualTo(303L);
        verify(publisher, never()).publishEvent(any());
        verify(contestSubmissionRateLimiter, never()).release(anyLong(), anyLong());
    }

    @Test
    void submitAsync_releasesRateLimitWhenContestStoreFails() {
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", 90L);
        Problem contestProblem = Problem.create("Contest Prob", contest, 1L);
        ReflectionTestUtils.setField(contestProblem, "id", 13L);
        when(problemRepository.findWithContestById(13L)).thenReturn(Optional.of(contestProblem));
        when(storeSelector.onContest(eq(contestProblem), any(LocalDateTime.class))).thenReturn(true);
        when(contestSubmissionRateLimiter.tryAcquire(90L, 5L)).thenReturn(Optional.empty());

        CompletableFuture<SubmissionStoreResult> storeFuture = new CompletableFuture<>();
        when(storeSelector.storeAsync(any(Submission.class))).thenReturn(storeFuture);

        CompletionStage<SubmissionReceipt> stage = submissionService.submitAsync(
                new SubmitSubmissionCommand(5L, 13L, "code")
        );
        storeFuture.completeExceptionally(new IllegalStateException("db error"));

        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("db error");
        verify(contestSubmissionRateLimiter).release(90L, 5L);
        verify(publisher, never()).publishEvent(any());
    }
}

