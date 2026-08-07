package my.oj.web.problem.api;

import my.oj.web.api.PageResponse;
import my.oj.web.auth.CurrentUser;
import my.oj.web.problem.ProblemNotFoundException;
import my.oj.web.problem.ProblemService;
import my.oj.web.problem.dto.ProblemDetailDto;
import my.oj.web.problem.dto.ProblemDto;
import my.oj.web.user.dto.UserDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
class ProblemApiController {

    private final ProblemService problemService;

    ProblemApiController(ProblemService problemService) {
        this.problemService = problemService;
    }

    /**
     * The page of problems, and which of them this user has already solved.
     *
     * <p>The solved set covers only the ids on this page. It is a second query either way, and
     * scoping it to the page is what keeps that query from growing with a user's history.
     */
    @GetMapping("/api/problems")
    ProblemPageResponse problems(@RequestParam(required = false, defaultValue = "") String problemName,
                                 @RequestParam(required = false) Long problemId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "30") int size,
                                 @CurrentUser UserDto currentUser) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        Page<ProblemDto> problems = problemService.searchProblems(problemName, problemId, pageable);
        List<Long> ids = problems.getContent().stream().map(ProblemDto::id).toList();
        Set<Long> solved = problemService.getSolvedProblemIds(currentUser.id(), ids);
        return new ProblemPageResponse(PageResponse.of(problems), solved);
    }

    @GetMapping("/api/problems/{problemId}")
    ProblemDetailDto problem(@PathVariable long problemId, @CurrentUser UserDto currentUser) {
        ProblemDetailDto detail = problemService.getProblemDetail(problemId, currentUser);
        if (detail == null) {
            throw new ProblemNotFoundException(problemId);
        }
        return detail;
    }

    record ProblemPageResponse(PageResponse<ProblemDto> problems, Set<Long> solvedProblemIds) {
    }
}
