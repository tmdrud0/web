package my.oj.web.user.api;

import my.oj.web.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Run with the perf profile, which is how the load-test stack runs it, because that is where this
 * endpoint's errors went wrong: the API advice was scoped to a list of packages that did not
 * include this one, so a malformed body reached {@code PerfExceptionHandler}'s catch-all and came
 * back 500. Outside that profile the same request got a bodyless 400. The advice is unscoped now
 * that there are no rendered pages for it to avoid, so there is no list left to forget.
 */
@WebMvcTest(LoginApiController.class)
@ActiveProfiles({"test", "perf"})
class LoginApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    @Test
    void aMalformedBodyIsABadRequestWithAJsonBody() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"\",\"pass\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MethodArgumentNotValidException"))
                .andExpect(jsonPath("$.path").value("/api/login"));
    }

    @Test
    void wrongCredentialsAreUnauthorized() throws Exception {
        given(userService.findByCredentials(any(), any())).willReturn(Optional.empty());

        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"loadtest_user_1\",\"pass\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("InvalidCredentials"));
    }
}
