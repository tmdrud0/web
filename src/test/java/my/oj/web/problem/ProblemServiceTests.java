package my.oj.web.problem;

import my.oj.web.contest.Contest;
import my.oj.web.problem.dto.ProblemDetailDto;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.accepted.AcceptedSubmissionRepository;
import my.oj.web.submission.dto.MinimalSubmissionDto;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProblemServiceTests {

    @Mock
    ProblemRepository problemRepository;

    @Mock
    SubmissionRepository submissionRepository;

    @Mock
    AcceptedSubmissionRepository acceptedSubmissionRepository;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    ProblemService problemService;

    private User lowSolvedUser;
    private User heavySolvedUser;

    @BeforeEach
    void setUp() {
        lowSolvedUser = User.withState(1L, "low", "p", 100L, new Streak());
        heavySolvedUser = User.withState(2L, "heavy", "p", 1000L, new Streak());
    }

    @Test
    void getSolvedProblemIds_returnsAllForLowSolvedUsers() {
        given(userRepository.findById(1L)).willReturn(Optional.of(lowSolvedUser));
        List<Long> currentProblems = LongStream.rangeClosed(1, 30).boxed().toList();
        given(acceptedSubmissionRepository.findSolvedProblemIdsByUserId(1L))
                .willReturn(List.of(1L, 2L, 3L));

        Set<Long> result = problemService.getSolvedProblemIds(1L, currentProblems);

        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
        then(acceptedSubmissionRepository).should().findSolvedProblemIdsByUserId(1L);
        then(acceptedSubmissionRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void getSolvedProblemIds_filtersForHeavyUsers() {
        given(userRepository.findById(2L)).willReturn(Optional.of(heavySolvedUser));
        List<Long> currentProblems = LongStream.rangeClosed(1, 30).boxed().toList();
        given(acceptedSubmissionRepository.findSolvedProblemIdsInList(2L, currentProblems))
                .willReturn(List.of(5L, 6L));

        Set<Long> result = problemService.getSolvedProblemIds(2L, currentProblems);

        assertThat(result).containsExactlyInAnyOrder(5L, 6L);
        then(acceptedSubmissionRepository).should().findSolvedProblemIdsInList(2L, currentProblems);
        then(acceptedSubmissionRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void getProblemDetail_buildsDtoWithSubmissions() {
        UserDto userDto = new UserDto(lowSolvedUser.getId(), lowSolvedUser.getName(), lowSolvedUser.getSolvedCount(), lowSolvedUser.getStreak());

        Contest contest = org.mockito.Mockito.mock(Contest.class);
        given(contest.getId()).willReturn(50L);
        given(contest.getName()).willReturn("Contest");

        Problem mockProblem = org.mockito.Mockito.mock(Problem.class);
        given(mockProblem.getId()).willReturn(99L);
        given(mockProblem.getName()).willReturn("P");
        given(mockProblem.getContest()).willReturn(contest);
        given(mockProblem.getContestNum()).willReturn(1L);
        given(problemRepository.findById(99L)).willReturn(Optional.of(mockProblem));

        List<MinimalSubmissionDto> submissions = List.of(new MinimalSubmissionDto(7L, null, LocalDateTime.now()));
        given(submissionRepository.findSubmissionDtosByUserIdAndProblemId(userDto.id(), mockProblem.getId()))
                .willReturn(submissions);

        ProblemDetailDto detail = problemService.getProblemDetail(99L, userDto);

        assertThat(detail).isNotNull();
        assertThat(detail.userSubmissions()).containsExactlyElementsOf(submissions);
        assertThat(detail.id()).isEqualTo(99L);
        assertThat(detail.contestId()).isEqualTo(50L);
    }
}
