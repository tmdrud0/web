package my.oj.web.perf;

import jakarta.servlet.http.HttpServletRequest;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PerfExceptionHandlerTests {

    @Test
    void handleOverloaded_returnsServiceUnavailableWithRetryAfter() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getRequestURI()).willReturn("/perf/contest/seed");

        var response = new PerfExceptionHandler().handleOverloaded(
                new ContestSubmissionOverloadedException(),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).containsEntry("error", "ContestSubmissionOverloadedException")
                .containsEntry("path", "/perf/contest/seed");
    }

    @Test
    void asyncOverload_isMappedToServiceUnavailable() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AsyncOverloadController())
                .setControllerAdvice(new PerfExceptionHandler())
                .build();

        MvcResult asyncResult = mockMvc.perform(get("/async-overload"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    @RestController
    static class AsyncOverloadController {

        @GetMapping("/async-overload")
        CompletionStage<Map<String, Object>> overload() {
            return CompletableFuture.failedFuture(new ContestSubmissionOverloadedException());
        }
    }
}
