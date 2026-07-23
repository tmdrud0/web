package my.oj.web.submission;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.auth.CurrentUser;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import my.oj.web.problem.ProblemRepository;
import my.oj.web.submission.dto.SubmissionFormDto;
import my.oj.web.submission.dto.SubmissionReceipt;
import my.oj.web.submission.dto.SubmissionSummaryDto;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import my.oj.web.user.dto.UserDto;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.beans.PropertyEditorSupport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

@Controller
@RequiredArgsConstructor
@Slf4j
public class SubmissionController {

    private final SubmissionRepository submissionRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionService submissionService;

    @GetMapping("/submissions")
    public String listSubmissions(
            @RequestParam(required = false, defaultValue = "") String user,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "false") boolean acceptedOnly,
            @CurrentUser UserDto currentUser,
            Model model) {

        SubmissionSortOrder sortOrder = SubmissionSortOrder.from(order);
        Slice<SubmissionSummaryDto> submissionsSlice =
                submissionRepository.findSummaries(user, problemId, lastId, size, sortOrder, acceptedOnly);

        model.addAttribute("submissionsSlice", submissionsSlice);
        model.addAttribute("userFilter", user);
        model.addAttribute("problemFilter", problemId);
        model.addAttribute("orderFilter", sortOrder.name().toLowerCase());
        model.addAttribute("acceptedOnlyFilter", acceptedOnly);
        model.addAttribute("pageSize", size);
        return "submissions";
    }

    @GetMapping("/problems/{id}/submission")
    public String showSubmissionForm(@PathVariable Long id,
                                     @CurrentUser UserDto currentUser,
                                     Model model) {
        var problem = problemRepository.findDtoById(id);
        if (problem == null) {
            return "redirect:/problems";
        }

        model.addAttribute("problem", problem);
        return "submissionForm";
    }

    @PostMapping("/problems/{id}/submission")
    public CompletionStage<String> submit(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") SubmissionFormDto form,
            BindingResult binding,
            @CurrentUser UserDto currentUser,
            RedirectAttributes redirectAttributes) {

        if (binding.hasErrors()) {
            redirectAttributes.addFlashAttribute("error", "Invalid submission form");
            return CompletableFuture.completedFuture("redirect:/problem/" + id);
        }

        try {
            return submissionService.submitAsync(
                    new SubmitSubmissionCommand(
                            currentUser.id(), id, form.code()
                    )
            ).handle((receipt, error) -> {
                if (error != null) {
                    Throwable cause = unwrapCompletionException(error);
                    if (cause instanceof IllegalArgumentException || cause instanceof ContestSubmissionOverloadedException) {
                        redirectAttributes.addFlashAttribute("error", cause.getMessage());
                        return "redirect:/problem/" + id;
                    }
                    throw new CompletionException(cause);
                }

                if (receipt.isDuplicate()) {
                    redirectAttributes.addFlashAttribute("message", "An identical submission already exists (ID #" + receipt.submissionId() + ").");
                    return "redirect:/problem/" + id;
                }

                if (receipt.isContest()) {
                    redirectAttributes.addFlashAttribute("message", "Contest submission has been queued");
                    return "redirect:/problem/" + id;
                }

                return "redirect:/submission/" + receipt.submissionId();
            });
        } catch (IllegalArgumentException | ContestSubmissionOverloadedException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return CompletableFuture.completedFuture("redirect:/problem/" + id);
        }
    }

    private static Throwable unwrapCompletionException(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @GetMapping("/submission/{id}")
    public String viewSubmissionCode(@PathVariable Long id,
                                     @CurrentUser UserDto currentUser,
                                     Model model) {
        var submissionView = submissionRepository.findViewById(id);

        if (submissionView.isEmpty()) {
            return "redirect:/submissions";
        }

        model.addAttribute("submission", submissionView.get());
        return "submission";
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
                    setValue(null);
                }
            }
        });
    }
}


