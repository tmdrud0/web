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

    private static final long SOLVED_COUNT_MULTIPLIER = 4L;

    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final AcceptedSubmissionRepository acceptedRepository;
    private final UserRepository userRepository;

    public Page<ProblemDto> searchProblems(String problemName, Long problemId, Pageable pageable) {
        return problemRepository.searchProblems(problemName, problemId, pageable);
    }

    public Set<Long> getSolvedProblemIds(Long userId, List<Long> currentProblemIds) {
        User user = userRepository.findById(userId).orElseThrow();
        List<Long> problemIds = currentProblemIds != null ? currentProblemIds : List.of();

        if (problemIds.isEmpty()) {
            return Set.of();
        }

        long dynamicThreshold = problemIds.size() * SOLVED_COUNT_MULTIPLIER;

        if (user.getSolvedCount() < dynamicThreshold) {
            return new HashSet<>(acceptedRepository.findSolvedProblemIdsByUserId(userId));
        }

        return new HashSet<>(acceptedRepository.findSolvedProblemIdsInList(userId, problemIds));
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
