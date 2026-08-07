package my.oj.web.problem.api;

import my.oj.web.auth.CurrentUserArgumentResolver;
import my.oj.web.problem.ProblemService;
import my.oj.web.problem.dto.ProblemDetailDto;
import my.oj.web.problem.dto.ProblemDto;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProblemApiController.class)
@Import(CurrentUserArgumentResolver.class)
class ProblemApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProblemService problemService;

    @Test
    void theProblemListCarriesThePageAndTheSolvedSetForIt() throws Exception {
        given(problemService.searchProblems(any(), any(), any())).willReturn(new PageImpl<>(
                List.of(new ProblemDto(1L, "A", 7L, "Weekly", 1L), new ProblemDto(2L, "B", 7L, "Weekly", 2L)),
                PageRequest.of(0, 30),
                2
        ));
        given(problemService.getSolvedProblemIds(anyLong(), any())).willReturn(Set.of(1L));

        mockMvc.perform(authenticated(get("/api/problems")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problems.content.length()").value(2))
                .andExpect(jsonPath("$.problems.totalElements").value(2))
                .andExpect(jsonPath("$.solvedProblemIds.length()").value(1))
                .andExpect(jsonPath("$.solvedProblemIds[0]").value(1));
    }

    @Test
    void anUnknownProblemIsNotFound() throws Exception {
        given(problemService.getProblemDetail(anyLong(), any())).willReturn(null);

        mockMvc.perform(authenticated(get("/api/problems/404")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ProblemNotFoundException"));
    }

    @Test
    void aProblemCarriesItsContestAndTheCallersOwnSubmissions() throws Exception {
        given(problemService.getProblemDetail(anyLong(), any())).willReturn(
                new ProblemDetailDto(1L, "A", 7L, "Weekly", 1L, List.of())
        );

        mockMvc.perform(authenticated(get("/api/problems/1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.contestName").value("Weekly"))
                .andExpect(jsonPath("$.userSubmissions").isArray());
    }

    /**
     * Both reads are about the caller, so both need one. The page redirected an anonymous visitor
     * to a login screen; there is no screen to send them to.
     */
    @Test
    void anAnonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/problems"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UnauthenticatedException"));
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder.sessionAttr("user", new UserDto(1L, "alice", 0L, null));
    }
}
