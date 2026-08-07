package my.oj.web.contest.api;

import my.oj.web.api.PageResponse;
import my.oj.web.contest.ContestService;
import my.oj.web.contest.dto.ContestSummaryView;
import my.oj.web.problem.dto.ContestProblemDto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
class ContestApiController {

    private final ContestService contestService;

    ContestApiController(ContestService contestService) {
        this.contestService = contestService;
    }

    @GetMapping("/api/contests")
    PageResponse<ContestSummaryView> contests(@PageableDefault(size = 10) Pageable pageable) {
        return PageResponse.of(contestService.getSummaries(pageable));
    }

    @GetMapping("/api/contests/{contestId}")
    ContestDetailResponse contest(@PathVariable long contestId) {
        return ContestDetailResponse.from(contestService.getDetail(contestId), LocalDateTime.now());
    }

    @GetMapping("/api/contests/{contestId}/problems")
    List<ContestProblemDto> problems(@PathVariable long contestId) {
        return contestService.getProblems(contestId);
    }
}
