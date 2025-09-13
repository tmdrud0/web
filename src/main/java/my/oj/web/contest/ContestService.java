package my.oj.web.contest;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.finalization.ContestFinalizationService;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.problem.dto.ContestProblemDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContestService {

    private final ContestRepository contestRepository;
    private final ProblemRepository problemRepository;
    private final ContestFinalizationService finalizationService;

    @Transactional(readOnly = true)
    public Optional<ContestDetailDto> findDetailById(Long contestId) {
        var detail = contestRepository.findDetailById(contestId);
        if (detail.isEmpty()) {
            return detail;
        }

        List<ContestProblemDto> problems = problemRepository.findDtoByContestId(contestId);
        return detail.map(dto -> dto.withProblems(problems));
    }

    @Transactional
    public void finalizeContest(Long contestId) {
        finalizationService.finalizeContest(contestId);
    }

}


