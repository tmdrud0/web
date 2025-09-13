package my.oj.web.problem;

import lombok.RequiredArgsConstructor;
import my.oj.web.problem.dto.ProblemDto;
import my.oj.web.problem.dto.ProblemDetailDto;
import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.accepted.AcceptedSubmissionRepository;
import my.oj.web.submission.dto.MinimalSubmissionDto;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.dto.UserDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final AcceptedSubmissionRepository acceptedRepository;
    private final UserRepository userRepository;

    public Page<ProblemDto> searchProblems(String problemName, Long problemId, Pageable pageable) {
        return problemRepository.searchProblems(problemName, problemId, pageable);
    }

    public Set<Long> getSolvedProblemIds(Long userId, List<Long> currentProblemIds) {
        final Long SOLVED_COUNT_THRESHOLD = 500L;
        User user = userRepository.findById(userId).orElseThrow();

        if (user.getSolvedCount() < SOLVED_COUNT_THRESHOLD)
            return new HashSet<>(acceptedRepository.findSolvedProblemIdsByUserId(userId));
        else
            return new HashSet<>(acceptedRepository.findSolvedProblemIdsInList(userId, currentProblemIds));
    }

    public ProblemDetailDto getProblemDetail(Long problemId, UserDto user) {
        Problem problem = problemRepository.findById(problemId).orElse(null);
        if (problem == null) return null;

        List<MinimalSubmissionDto> submissions =
                submissionRepository.findSubmissionDtosByUserIdAndProblemId(user.id(), problem.getId());

        return new ProblemDetailDto(
                problem.getId(), problem.getName(),
                problem.getContest().getId(), problem.getContest().getName(),
                problem.getContestNum(),
                submissions);

    }
}