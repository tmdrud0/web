package my.oj.web.problem;

import lombok.RequiredArgsConstructor;
import my.oj.web.problem.dto.ProblemDetailDto;
import my.oj.web.problem.dto.ProblemDto;
import my.oj.web.user.dto.UserDto;
import my.oj.web.auth.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.beans.PropertyEditorSupport;
import java.util.List;
import java.util.Set;


@Controller
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;

    @GetMapping("/problems")
    public String listProblems(
            @RequestParam(required = false, defaultValue = "") String problemName,
            @RequestParam(required = false) Long problemId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @CurrentUser UserDto currentUser,
            Model model) {

        Pageable pageable = PageRequest.of(page, size);
        Page<ProblemDto> problemsPage = problemService.searchProblems(problemName, problemId, pageable);
        List<Long> currentProblemIds = problemsPage.getContent().stream()
                .map(ProblemDto::id)
                .toList();

        Set<Long> solvedProblemIds = problemService.getSolvedProblemIds(currentUser.id(),currentProblemIds);

        model.addAttribute("problemsPage", problemsPage);
        model.addAttribute("problemNameFilter", problemName);
        model.addAttribute("problemIdFilter", problemId);
        model.addAttribute("solvedProblemIds", solvedProblemIds);

        return "problems";
    }

    @GetMapping("/problem/{id}")
    public String showProblem(@PathVariable Long id,
                               @CurrentUser UserDto currentUser,
                               Model model) {

        ProblemDetailDto detailDto = problemService.getProblemDetail(id, currentUser);
        if (detailDto == null) return "redirect:/problems";

        model.addAttribute("problem", detailDto);
        return "problem";
    }

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (text == null || text.trim().isEmpty()) {
                    setValue(null);
                    return;
                }
                try {
                    setValue(Long.parseLong(text.trim()));
                } catch (NumberFormatException e) {
                    setValue(null); // fallback to null
                }
            }
        });
    }
}




