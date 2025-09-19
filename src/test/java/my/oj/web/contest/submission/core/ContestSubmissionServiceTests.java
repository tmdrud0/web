package my.oj.web.contest.submission.core;

import my.oj.web.contest.Contest;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import my.oj.web.problem.Problem;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestSubmissionServiceTests {

    @Mock
    private ContestSubmissionRepository submissionRepository;

    @Mock
    private ContestSubmissionResultRepository resultRepository;

    @Mock
    private ContestScoreboardOutboxService outboxService;

    @Mock
    private ApplicationEventPublisher publisher;

    @InjectMocks
    private ContestSubmissionService contestSubmissionService;

    private User user;
    private Problem problem;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        user = User.withState(10L, "alice", "pass", 0L, new Streak());
        Contest contest = new Contest("Test Contest");
        ReflectionTestUtils.setField(contest, "id", 100L);
        problem = Problem.create("P1", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 200L);
        now = LocalDateTime.now();
    }

    @Test
    void create_savesNewSubmission_WhenUnique() {
        given(submissionRepository.save(any(ContestSubmission.class)))
                .willAnswer(invocation -> {
                    ContestSubmission saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 500L);
                    return saved;
                });

        ContestSubmissionService.ContestSubmissionCreateResult result =
                contestSubmissionService.create(user, problem, "print(1)", now);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.submission().getId()).isEqualTo(500L);
        verify(submissionRepository, times(1)).save(any(ContestSubmission.class));
    }

    @Test
    void create_returnsExistingSubmission_WhenDuplicate() {
        ContestSubmission existing = ContestSubmission.create(user, problem, "print(1)", "hash", now.minusMinutes(1));
        ReflectionTestUtils.setField(existing, "id", 777L);

        given(submissionRepository.save(any())).willThrow(new DataIntegrityViolationException("duplicate"));
        given(submissionRepository.findByContestIdAndProblemIdAndUserIdAndCodeHash(anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(Optional.of(existing));

        ContestSubmissionService.ContestSubmissionCreateResult result =
                contestSubmissionService.create(user, problem, "print(1)", now);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.submission().getId()).isEqualTo(777L);
        verify(submissionRepository).findByContestIdAndProblemIdAndUserIdAndCodeHash(anyLong(), anyLong(), anyLong(), anyString());
    }
}

