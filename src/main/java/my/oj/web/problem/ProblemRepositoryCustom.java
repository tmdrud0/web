package my.oj.web.problem;

import my.oj.web.problem.dto.ProblemDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProblemRepositoryCustom {
    Page<ProblemDto> searchProblems(String problemName, Long problemId, Pageable pageable);
}
