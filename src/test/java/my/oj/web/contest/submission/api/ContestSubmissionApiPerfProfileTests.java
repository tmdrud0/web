package my.oj.web.contest.submission.api;

import my.oj.web.api.JsonApiExceptionHandler;
import my.oj.web.auth.CurrentUserArgumentResolver;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import my.oj.web.problem.ProblemNotFoundException;
import my.oj.web.submission.SubmissionService;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.ControllerAdviceBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same endpoint with the perf profile on, which is how the load-test stack runs it.
 *
 * <p>That profile adds {@code PerfExceptionHandler}, an unscoped advice with a catch-all handler
 * and no order of its own. It exists for the /perf endpoints and there is nothing in it that says
 * so, which is why the API advice takes highest precedence: without that, whether an
 * unauthenticated submission answered 401, a redirect, or a 500 depended on which advice the
 * context happened to sort first. The load run that motivated this measured a 302.
 */
@WebMvcTest(ContestSubmissionApiController.class)
@Import(CurrentUserArgumentResolver.class)
@ActiveProfiles({"test", "perf"})
class ContestSubmissionApiPerfProfileTests {

    private static final String BODY = "{\"code\":\"int main(){return 0;}\"}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext applicationContext;

    @MockitoBean
    SubmissionService submissionService;

    /**
     * The statuses below only come out right because this advice is consulted first, and the
     * status assertions on their own do not prove that: the resolver walks the advice beans in
     * order and takes the first one with a handler for the exception, so whichever advice a
     * classpath scan happened to register first can produce the same answers by luck. Asserting
     * the precedence itself is what fails when the {@code @Order} is dropped - the case that
     * turned an unauthenticated submission into a 302, and that a scan-order change could
     * reintroduce without touching this endpoint at all.
     */
    @Test
    void theJsonApiAdviceOutranksEveryOtherAdvice() {
        List<ControllerAdviceBean> advices = ControllerAdviceBean.findAnnotatedBeans(applicationContext);

        assertThat(advices).extracting(ControllerAdviceBean::getBeanType)
                .contains(JsonApiExceptionHandler.class);
        ControllerAdviceBean jsonApiAdvice = advices.stream()
                .filter(advice -> JsonApiExceptionHandler.class.equals(advice.getBeanType()))
                .findFirst()
                .orElseThrow();
        assertThat(jsonApiAdvice.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(advices.get(0).getBeanType()).isEqualTo(JsonApiExceptionHandler.class);
    }

    @Test
    void anUnauthenticatedSubmissionIsStillUnauthorized() throws Exception {
        mockMvc.perform(post("/api/problems/1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UnauthenticatedException"));
    }

    @Test
    void aMissingProblemIsStillNotFound() throws Exception {
        given(submissionService.submitAsync(any())).willThrow(new ProblemNotFoundException(4242L));

        mockMvc.perform(post("/api/problems/4242/submissions")
                        .sessionAttr("user", new UserDto(1L, "loadtest_user_1", 0L, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void anOverloadedWriterIsStillServiceUnavailableWithRetryAfter() throws Exception {
        given(submissionService.submitAsync(any())).willThrow(new ContestSubmissionOverloadedException());

        mockMvc.perform(post("/api/problems/1/submissions")
                        .sessionAttr("user", new UserDto(1L, "loadtest_user_1", 0L, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }
}
