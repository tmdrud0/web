package my.oj.web.contest.scoreboard.api;

import my.oj.web.auth.CurrentUserArgumentResolver;
import my.oj.web.contest.ContestService;
import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestScoreboardRow;
import my.oj.web.contest.scoreboard.ContestScoreboardView;
import my.oj.web.contest.scoreboard.ContestScoreboardViewAssembler;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two scoreboard views the hot endpoint deliberately does not serve. They survived the page
 * that used to be the only way to reach them, so these pin that they are still reachable.
 */
@WebMvcTest(ContestScoreboardViewApiController.class)
@Import(CurrentUserArgumentResolver.class)
class ContestScoreboardViewApiControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContestScoreboardViewAssembler assembler;

    @MockitoBean
    ContestService contestService;

    @Test
    void theFinalScoreboardCarriesNamesAndCompetitionRanks() throws Exception {
        given(assembler.assemble(anyLong(), anyBoolean(), any(), anyBoolean(), any())).willReturn(
                new ContestScoreboardView(
                        List.of(
                                new ContestScoreboardRow(1, 10L, "alice", 5, 100L),
                                new ContestScoreboardRow(1, 11L, "bob", 5, 100L),
                                new ContestScoreboardRow(3, 12L, "carol", 4, 250L)
                        ),
                        1L, 3L, 100, false, null, null, 1L
                )
        );

        mockMvc.perform(get("/api/contests/7/scoreboard/final"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].userName").value("alice"))
                .andExpect(jsonPath("$.rows[1].rank").value(1))
                .andExpect(jsonPath("$.rows[2].rank").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        verify(assembler).assemble(eq(7L), eq(true), any(), eq(false), any());
    }

    @Test
    void aroundMeNeedsAUser() throws Exception {
        mockMvc.perform(get("/api/contests/7/scoreboard/around-me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UnauthenticatedException"));
    }

    /**
     * A finished contest's neighbourhood comes from MySQL and a running one's from Redis, so the
     * view has to know which before it can answer.
     */
    @Test
    void aroundMeAsksTheContestWhetherItIsFinalised() throws Exception {
        given(contestService.getDetail(7L)).willReturn(new ContestDetailDto(
                7L, "Weekly", LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(1),
                LocalDateTime.now().minusMinutes(5)
        ));
        given(assembler.assemble(anyLong(), anyBoolean(), any(), anyBoolean(), any())).willReturn(
                new ContestScoreboardView(
                        List.of(new ContestScoreboardRow(6, 10L, "alice", 3, 400L)),
                        6L, 20L, 100, true, null, null, null
                )
        );

        mockMvc.perform(get("/api/contests/7/scoreboard/around-me")
                        .sessionAttr("user", new UserDto(10L, "alice", 0L, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aroundMe").value(true))
                .andExpect(jsonPath("$.rows[0].userId").value(10));

        verify(assembler).assemble(eq(7L), eq(true), any(), eq(true), any());
    }
}
