package my.oj.web.contest.api;

import my.oj.web.contest.ContestNotFoundException;
import my.oj.web.contest.ContestService;
import my.oj.web.contest.ContestStatus;
import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestSummaryView;
import my.oj.web.problem.dto.ContestProblemDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContestApiController.class)
class ContestApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContestService contestService;

    /**
     * The detail carries {@code finalized}, which the page answered by loading the contest entity a
     * second time after the projection it had already read. One query now, and the flag is in the
     * projection.
     */
    @Test
    void contestDetailCarriesStatusAndFinalizedWithoutTheProblemList() throws Exception {
        given(contestService.getDetail(7L)).willReturn(new ContestDetailDto(
                7L,
                "Weekly",
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1),
                null
        ));

        mockMvc.perform(get("/api/contests/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Weekly"))
                .andExpect(jsonPath("$.status").value(ContestStatus.RUNNING.name()))
                .andExpect(jsonPath("$.finalized").value(false))
                .andExpect(jsonPath("$.timeMessage").exists())
                .andExpect(jsonPath("$.problems").doesNotExist());
    }

    @Test
    void aFinalisedContestSaysSo() throws Exception {
        given(contestService.getDetail(7L)).willReturn(new ContestDetailDto(
                7L,
                "Weekly",
                LocalDateTime.now().minusHours(3),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().minusMinutes(30)
        ));

        mockMvc.perform(get("/api/contests/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ContestStatus.ENDED.name()))
                .andExpect(jsonPath("$.finalized").value(true));
    }

    @Test
    void anUnknownContestIsNotFound() throws Exception {
        willThrow(new ContestNotFoundException(404L)).given(contestService).getDetail(404L);

        mockMvc.perform(get("/api/contests/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ContestNotFoundException"));
    }

    @Test
    void theProblemListIsItsOwnResource() throws Exception {
        given(contestService.getProblems(7L)).willReturn(List.of(
                new ContestProblemDto(11L, "A", 1L, 30L),
                new ContestProblemDto(12L, "B", 2L, 5L)
        ));

        mockMvc.perform(get("/api/contests/7/problems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[1].contestNum").value(2));
    }

    /**
     * Paged on this application's terms rather than as a serialised {@code PageImpl}, whose JSON is
     * the shape of its getters and carries a {@code Pageable} the caller has no use for.
     */
    @Test
    void theContestListIsAPageOfSummaries() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        given(contestService.getSummaries(any())).willReturn(new PageImpl<>(
                List.of(new ContestSummaryView(1L, "Weekly", now, now.plusHours(2),
                        ContestStatus.RUNNING, "Running", "Time left 02:00:00")),
                PageRequest.of(0, 10),
                1
        ));

        mockMvc.perform(get("/api/contests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Weekly"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void anUnknownContestsProblemListIsNotFound() throws Exception {
        willThrow(new ContestNotFoundException(404L)).given(contestService).getProblems(anyLong());

        mockMvc.perform(get("/api/contests/404/problems"))
                .andExpect(status().isNotFound());
    }
}
