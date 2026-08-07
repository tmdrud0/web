package my.oj.web.contest.submission.api;

import my.oj.web.auth.CurrentUserArgumentResolver;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import my.oj.web.contest.submission.support.ContestSubmissionRateLimitExceededException;
import my.oj.web.problem.ProblemNotFoundException;
import my.oj.web.submission.SubmissionOrigin;
import my.oj.web.submission.SubmissionService;
import my.oj.web.submission.dto.SubmissionReceipt;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContestSubmissionApiController.class)
@Import(CurrentUserArgumentResolver.class)
class ContestSubmissionApiControllerTests {

    private static final String BODY = "{\"code\":\"int main(){return 0;}\"}";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SubmissionService submissionService;

    /**
     * 401 rather than the 302 to /login that a page would send. Two advices can answer this - the
     * one that redirects, for the rendered pages, and the JSON one - and which wins is decided by
     * order alone. Removing that {@code @Order} is a one-character change that turns every
     * unauthenticated API call into a 200-with-a-login-page as far as a JSON client can tell.
     */
    @Test
    void anUnauthenticatedSubmissionIsUnauthorizedRatherThanARedirect() throws Exception {
        mockMvc.perform(post("/api/problems/1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UnauthenticatedException"));

        verifyNoInteractions(submissionService);
    }

    @Test
    void aSubmissionForAProblemThatDoesNotExistIsNotFound() throws Exception {
        given(submissionService.submitAsync(any())).willThrow(new ProblemNotFoundException(4242L));

        mockMvc.perform(authenticated(post("/api/problems/4242/submissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ProblemNotFoundException"));
    }

    /**
     * Backpressure, not a fault: the writer cannot queue this submission and says when to come
     * back. A load generator that honours Retry-After is behaving correctly, so the header is part
     * of the contract rather than decoration.
     */
    @Test
    void anOverloadedWriterIsServiceUnavailableWithRetryAfter() throws Exception {
        given(submissionService.submitAsync(any())).willThrow(new ContestSubmissionOverloadedException());

        mockMvc.perform(authenticated(post("/api/problems/1/submissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("ContestSubmissionOverloadedException"));
    }

    /**
     * The cooldown's sibling, one status along. It answered 400 because the exception happens to
     * extend {@code IllegalArgumentException}, which told the caller its request was malformed
     * when the right answer is to send the same request again after the stated wait.
     */
    @Test
    void theSubmissionCooldownIsTooManyRequestsWithRetryAfter() throws Exception {
        given(submissionService.submitAsync(any()))
                .willThrow(new ContestSubmissionRateLimitExceededException(Duration.ofMillis(2500)));

        mockMvc.perform(authenticated(post("/api/problems/1/submissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.error").value("ContestSubmissionRateLimitExceededException"));
    }

    @Test
    void anAcceptedSubmissionIsAcceptedWithItsReceipt() throws Exception {
        given(submissionService.submitAsync(any())).willReturn(
                CompletableFuture.completedFuture(new SubmissionReceipt(99L, SubmissionOrigin.CONTEST, false))
        );

        MvcResult started = mockMvc.perform(authenticated(post("/api/problems/1/submissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.submissionId").value(99))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.sessionAttr("user", new UserDto(1L, "loadtest_user_1", 0L, null));
    }
}
