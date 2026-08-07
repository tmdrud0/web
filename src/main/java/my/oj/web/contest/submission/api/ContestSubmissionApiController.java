package my.oj.web.contest.submission.api;

import jakarta.validation.Valid;
import my.oj.web.auth.CurrentUser;
import my.oj.web.submission.SubmissionService;
import my.oj.web.submission.dto.SubmitSubmissionCommand;
import my.oj.web.user.dto.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletionStage;

/**
 * Submitting a solution, without a form or a redirect.
 *
 * <p>The same {@link SubmissionService#submitAsync} the page handler calls - the pipeline behind
 * this is not a separate path and must not become one. What is gone is the shape around it: a form
 * POST answered with 303 and a follow-up GET of a rendered confirmation, which made every
 * submission two requests and put Thymeleaf on the write path.
 *
 * <p>The identity comes from the session rather than the body. The perf endpoint this replaces
 * took a {@code userId} field, which made it convenient to drive under load and meant the
 * authentication cost was never in any measurement.
 */
@RestController
class ContestSubmissionApiController {

    private final SubmissionService submissionService;

    ContestSubmissionApiController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @PostMapping("/api/problems/{problemId}/submissions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CompletionStage<ContestSubmissionResponse> submit(@PathVariable Long problemId,
                                                      @Valid @RequestBody ContestSubmissionRequest request,
                                                      @CurrentUser UserDto currentUser) {
        return submissionService.submitAsync(
                new SubmitSubmissionCommand(currentUser.id(), problemId, request.code())
        ).thenApply(ContestSubmissionResponse::from);
    }
}
