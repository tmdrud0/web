package my.oj.web.contest;

import my.oj.web.contest.dto.ContestDetailDto;
import my.oj.web.contest.dto.ContestScoreboardRow;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestControllerTests {

    @Mock
    private ContestService contestService;
    @Mock
    private ContestRepository contestRepository;
    @Mock
    private ContestScoreboardPageAssembler scoreboardPageAssembler;

    @Test
    void contestDetailKeepsScoreboardModelContract() {
        long contestId = 42L;
        ContestDetailDto contest = new ContestDetailDto(
                contestId,
                "Weekly Contest",
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1)
        );
        Contest contestEntity = mock(Contest.class);
        UserDto currentUser = new UserDto(7L, "current", 0L, null);
        List<ContestScoreboardRow> rows = List.of(
                new ContestScoreboardRow(1, 7L, "current", 3, 120L)
        );
        ContestScoreboardPageView pageView = new ContestScoreboardPageView(
                rows,
                1L,
                150L,
                100,
                true,
                Optional.of(1L),
                Optional.of(101L),
                Optional.of(51L)
        );

        when(contestService.findDetailById(contestId)).thenReturn(Optional.of(contest));
        when(contestRepository.findById(contestId)).thenReturn(Optional.of(contestEntity));
        when(contestEntity.isFinalized()).thenReturn(true);
        when(scoreboardPageAssembler.assemble(contestId, true, currentUser, true, 51L))
                .thenReturn(pageView);

        ExtendedModelMap model = new ExtendedModelMap();
        ContestController controller = new ContestController(
                contestService,
                contestRepository,
                scoreboardPageAssembler
        );

        String viewName = controller.contestDetail(
                contestId,
                "scoreboard",
                51L,
                true,
                currentUser,
                model
        );

        assertThat(viewName).isEqualTo("contest");
        assertThat(model)
                .containsEntry("contest", contest)
                .containsEntry("status", ContestStatus.RUNNING)
                .containsEntry("statusLabel", ContestStatus.RUNNING.getLabel())
                .containsEntry("activeTab", "scoreboard")
                .containsEntry("scoreboardRows", rows)
                .containsEntry("hasScoreboard", true)
                .containsEntry("scoreboardTotalParticipants", 150L)
                .containsEntry("scoreboardStartRank", 1L)
                .containsEntry("scoreboardPageSize", 100)
                .containsEntry("scoreboardPrevCursor", 1L)
                .containsEntry("scoreboardNextCursor", 101L)
                .containsEntry("scoreboardAroundMe", true)
                .containsEntry("scoreboardCursor", 51L)
                .containsEntry("scoreboardFinalized", true)
                .containsEntry("currentUserId", 7L)
                .containsKey("timeMessage");
    }
}
