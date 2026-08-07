package my.oj.web.contest.scoreboard.api;

import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContestScoreboardApiController.class)
class ContestScoreboardApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContestScoreboardService contestScoreboardService;

    /**
     * The endpoint this replaced answered with the size of the page and threw the rows away, so a
     * response carrying no entries was indistinguishable from a correct one. Asserting on the
     * entries rather than on the count is the point of the test.
     */
    @Test
    void theResponseCarriesTheEntriesAndNotJustTheirCount() throws Exception {
        given(contestScoreboardService.slice(anyLong(), anyLong(), anyInt())).willReturn(
                new ContestScoreboardSlice(7L, 1L, List.of(
                        new ContestScoreboardEntry(11L, 5, 100L),
                        new ContestScoreboardEntry(12L, 4, 250L)
                ), 2L)
        );

        mockMvc.perform(get("/api/contests/7/scoreboard").param("startRank", "1").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contestId").value(7))
                .andExpect(jsonPath("$.totalParticipants").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].userId").value(11))
                .andExpect(jsonPath("$.entries[0].solvedCount").value(5))
                .andExpect(jsonPath("$.entries[0].penalty").value(100))
                .andExpect(jsonPath("$.entries[1].userId").value(12));
    }

    /**
     * The same competition ranking the rendered scoreboard applies. Numbering by position instead
     * gave 1, 2, 3, 4 here, so the two views of one Redis state disagreed about who was leading.
     */
    @Test
    void tiedEntriesGetTheSameRank() throws Exception {
        given(contestScoreboardService.slice(anyLong(), anyLong(), anyInt())).willReturn(
                new ContestScoreboardSlice(7L, 1L, List.of(
                        new ContestScoreboardEntry(11L, 5, 100L),
                        new ContestScoreboardEntry(12L, 5, 100L),
                        new ContestScoreboardEntry(13L, 5, 100L),
                        new ContestScoreboardEntry(14L, 4, 100L)
                ), 4L)
        );

        mockMvc.perform(get("/api/contests/7/scoreboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].rank").value(1))
                .andExpect(jsonPath("$.entries[2].rank").value(1))
                .andExpect(jsonPath("$.entries[3].rank").value(4));
    }

    @Test
    void readingPastTheEndOfTheScoreboardIsAnEmptyPageRatherThanAnError() throws Exception {
        given(contestScoreboardService.slice(anyLong(), anyLong(), anyInt())).willReturn(
                new ContestScoreboardSlice(7L, 9_000L, List.of(), 620L)
        );

        mockMvc.perform(get("/api/contests/7/scoreboard").param("startRank", "9000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.totalParticipants").value(620));
    }
}
