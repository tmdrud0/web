package my.oj.web.submission.api;

import my.oj.web.submission.SubmissionRepository;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.submission.SubmissionSortOrder;
import my.oj.web.submission.dto.SubmissionSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubmissionApiController.class)
class SubmissionApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SubmissionRepository submissionRepository;

    @Test
    void submissionsAreAKeysetSliceWithNoTotal() throws Exception {
        given(submissionRepository.findSummaries(any(), any(), any(), anyInt(), any(), anyBoolean()))
                .willReturn(new SliceImpl<>(
                        List.of(summary(9L), summary(8L)),
                        PageRequest.of(0, 2),
                        true
                ));

        mockMvc.perform(get("/api/submissions").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(9))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    /**
     * The page bound {@code lastId} through a custom editor that turned anything unparseable into
     * null, so a broken cursor silently restarted from the top. A JSON caller gets told.
     */
    @Test
    void anUnparseableCursorIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/submissions").param("lastId", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theRequestedSizeIsBoundedBeforeItReachesTheQuery() throws Exception {
        given(submissionRepository.findSummaries(any(), any(), any(), anyInt(), any(), anyBoolean()))
                .willReturn(new SliceImpl<>(List.of()));

        mockMvc.perform(get("/api/submissions").param("size", "100000"))
                .andExpect(status().isOk());

        verify(submissionRepository).findSummaries(any(), any(), any(), eq(200), eq(SubmissionSortOrder.DESC), eq(false));
    }

    @Test
    void anUnknownSubmissionIsNotFound() throws Exception {
        given(submissionRepository.findViewById(anyLong())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/submissions/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SubmissionNotFoundException"));
    }

    private static SubmissionSummaryDto summary(long id) {
        return new SubmissionSummaryDto(id, 1L, "A", 2L, "bob", SubmissionResult.ACCEPTED, LocalDateTime.now());
    }
}
