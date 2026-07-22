package my.oj.web.contest;

import my.oj.web.contest.finalization.ContestFinalScore;
import my.oj.web.contest.finalization.ContestFinalScoreService;
import my.oj.web.contest.finalization.ContestFinalScoreStatus;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.ContestScoreboardSlice;
import my.oj.web.user.User;
import my.oj.web.user.UserRepository;
import my.oj.web.user.dto.UserDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardPageAssemblerTests {

    private static final long CONTEST_ID = 42L;

    @Mock
    private ContestScoreboardService contestScoreboardService;
    @Mock
    private ContestFinalScoreService contestFinalScoreService;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private ContestScoreboardPageAssembler assembler;

    @Test
    void assemblesLivePageWithTieDisplayRankAndUserFallback() {
        ContestScoreboardSlice slice = new ContestScoreboardSlice(CONTEST_ID, 1,
                List.of(
                        new ContestScoreboardEntry(10L, 3, 100L),
                        new ContestScoreboardEntry(11L, 3, 100L),
                        new ContestScoreboardEntry(12L, 2, 200L)
                ), 150L);
        User knownUser = user(10L, "known");
        when(contestScoreboardService.slice(CONTEST_ID, 1L, 100)).thenReturn(slice);
        when(userRepository.findAllById(any())).thenReturn(List.of(knownUser));

        ContestScoreboardPageView page = assembler.assemble(CONTEST_ID, false, null, false, null);

        assertThat(page.rows()).extracting(row -> row.rank()).containsExactly(1, 1, 3);
        assertThat(page.rows()).extracting(row -> row.userName()).containsExactly("known", "User #11", "User #12");
        assertThat(page.startRank()).isEqualTo(1L);
        assertThat(page.nextCursor()).contains(101L);
        assertThat(page.cursor()).contains(1L);
    }

    @Test
    void assemblesLiveAroundMeWindowWhenUserIsRanked() {
        ContestScoreboardSlice around = new ContestScoreboardSlice(CONTEST_ID, 8,
                List.of(new ContestScoreboardEntry(20L, 2, 100L)), 20L);
        User currentUser = user(20L, "me");
        when(contestScoreboardService.rankingAroundUser(CONTEST_ID, 20L, 11)).thenReturn(Optional.of(around));
        when(userRepository.findAllById(any())).thenReturn(List.of(currentUser));

        ContestScoreboardPageView page = assembler.assemble(CONTEST_ID, false, currentUser(20L), true, 101L);

        assertThat(page.aroundMe()).isTrue();
        assertThat(page.startRank()).isEqualTo(8L);
        assertThat(page.previousCursor()).isEmpty();
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.cursor()).isEmpty();
    }

    @Test
    void fallsBackToLastLivePageForOutOfRangeCursor() {
        ContestScoreboardSlice outOfRange = new ContestScoreboardSlice(CONTEST_ID, 301,
                List.of(), 205L);
        ContestScoreboardSlice lastPage = new ContestScoreboardSlice(CONTEST_ID, 201,
                List.of(new ContestScoreboardEntry(1L, 1, 1L)), 205L);
        User lastUser = user(1L, "last");
        when(contestScoreboardService.slice(CONTEST_ID, 301L, 100)).thenReturn(outOfRange);
        when(contestScoreboardService.slice(CONTEST_ID, 201L, 100)).thenReturn(lastPage);
        when(userRepository.findAllById(any())).thenReturn(List.of(lastUser));

        ContestScoreboardPageView page = assembler.assemble(CONTEST_ID, false, null, false, 301L);

        assertThat(page.startRank()).isEqualTo(201L);
        assertThat(page.previousCursor()).contains(101L);
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.cursor()).contains(201L);
    }

    @Test
    void returnsEmptyFinalPageWhenNoFinalScoresExist() {
        when(contestFinalScoreService.getScores(CONTEST_ID, ContestFinalScoreStatus.FINAL)).thenReturn(List.of());

        ContestScoreboardPageView page = assembler.assemble(CONTEST_ID, true, null, false, null);

        assertThat(page.rows()).isEmpty();
        assertThat(page.totalParticipants()).isZero();
        assertThat(page.startRank()).isEqualTo(1L);
        assertThat(page.cursor()).isEmpty();
    }

    @Test
    void assemblesFinalAroundMeWindow() {
        List<ContestFinalScore> scores = scores(20);
        User currentUser = user(11L, "me");
        when(contestFinalScoreService.getScores(CONTEST_ID, ContestFinalScoreStatus.FINAL)).thenReturn(scores);
        when(userRepository.findAllById(any())).thenReturn(List.of(currentUser));

        ContestScoreboardPageView page = assembler.assemble(CONTEST_ID, true, currentUser(11L), true, null);

        assertThat(page.aroundMe()).isTrue();
        assertThat(page.startRank()).isEqualTo(6L);
        assertThat(page.rows()).hasSize(11);
        assertThat(page.rows().get(5).userId()).isEqualTo(11L);
        assertThat(page.cursor()).isEmpty();
    }

    @Test
    void fallsBackToLastFinalPageForOutOfRangeCursor() {
        List<ContestFinalScore> scores = scores(205);
        when(contestFinalScoreService.getScores(CONTEST_ID, ContestFinalScoreStatus.FINAL)).thenReturn(scores);
        when(userRepository.findAllById(any())).thenReturn(List.of());

        ContestScoreboardPageView page = assembler.assemble(CONTEST_ID, true, null, false, 999L);

        assertThat(page.startRank()).isEqualTo(201L);
        assertThat(page.rows()).hasSize(5);
        assertThat(page.previousCursor()).contains(101L);
        assertThat(page.nextCursor()).isEmpty();
        assertThat(page.cursor()).contains(201L);
    }

    private List<ContestFinalScore> scores(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(rank -> ContestFinalScore.of(CONTEST_ID, rank, 1, rank, (int) rank,
                        ContestFinalScoreStatus.FINAL))
                .toList();
    }

    private UserDto currentUser(long id) {
        return new UserDto(id, "current", 0L, null);
    }

    private User user(long id, String name) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getName()).thenReturn(name);
        return user;
    }
}
