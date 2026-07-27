package my.oj.web.contest.finalization;

import my.oj.web.contest.Contest;
import my.oj.web.contest.scoreboard.ContestScoreboardEntry;
import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.InMemoryContestScoreboardStore;
import my.oj.web.contest.submission.core.ContestSubmission;
import my.oj.web.contest.submission.core.ContestSubmissionResult;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.problem.Problem;
import my.oj.web.submission.SubmissionResult;
import my.oj.web.user.Streak;
import my.oj.web.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ContestFinalScoreServiceTests {

    private static final long CONTEST_ID = 42L;
    private static final long BYSTANDER_USER_ID = 999L;

    @Mock
    private ContestSubmissionResultRepository resultRepository;
    @Mock
    private ContestFinalScoreRepository finalScoreRepository;

    /** A real scoreboard, not a mock, so mutations to it are observable. */
    private ContestScoreboardService liveScoreboard;
    private ContestFinalScoreService finalScoreService;

    private ContestSubmissionResult acceptedResult;
    private LocalDateTime contestStart;

    @BeforeEach
    void setUp() {
        liveScoreboard = new ContestScoreboardService(new InMemoryContestScoreboardStore());
        finalScoreService = new ContestFinalScoreService(resultRepository, finalScoreRepository, liveScoreboard);

        contestStart = LocalDateTime.of(2025, 9, 19, 9, 0);
        Contest contest = new Contest("Contest");
        ReflectionTestUtils.setField(contest, "id", CONTEST_ID);
        ReflectionTestUtils.setField(contest, "startTime", contestStart);
        ReflectionTestUtils.setField(contest, "endTime", LocalDateTime.of(2025, 9, 19, 13, 0));

        Problem problem = Problem.create("A", contest, 1L);
        ReflectionTestUtils.setField(problem, "id", 10L);

        User contestant = User.withState(1L, "contestant", "pass", 0L, new Streak());
        ContestSubmission submission = ContestSubmission.create(
                contestant, problem, "code", "hash", LocalDateTime.of(2025, 9, 19, 9, 30));
        ReflectionTestUtils.setField(submission, "id", 100L);

        acceptedResult = ContestSubmissionResult.pending(submission);
        ReflectionTestUtils.setField(acceptedResult, "id", 100L);
        acceptedResult.recordProvisional(SubmissionResult.ACCEPTED, LocalDateTime.of(2025, 9, 19, 9, 35));
        acceptedResult.recordFinal(SubmissionResult.ACCEPTED, LocalDateTime.of(2025, 9, 19, 9, 36));
    }

    /**
     * Ranking is computed by replaying every judgement into a scoreboard and reading the
     * standings back. That replay must not run on the live scoreboard: it resets before
     * and after, so readers would see the board wiped and refilled mid-finalization.
     */
    @Test
    void rebuildScores_leaves_the_live_scoreboard_untouched() {
        seedLiveScoreboard();

        finalScoreService.rebuildScores(CONTEST_ID, ContestFinalScoreStatus.FINAL, List.of(acceptedResult));

        List<ContestScoreboardEntry> live = liveScoreboard.currentRanking(CONTEST_ID);
        assertThat(live)
                .extracting(ContestScoreboardEntry::userId)
                .containsExactly(BYSTANDER_USER_ID);
    }

    @Test
    void rebuildScores_derives_ranking_from_the_replayed_results() {
        seedLiveScoreboard();

        finalScoreService.rebuildScores(CONTEST_ID, ContestFinalScoreStatus.FINAL, List.of(acceptedResult));

        ArgumentCaptorHelper captured = ArgumentCaptorHelper.capture(finalScoreRepository);
        assertThat(captured.scores())
                .extracting(ContestFinalScore::getUserId)
                .containsExactly(1L);
        assertThat(captured.scores())
                .extracting(ContestFinalScore::getSolvedCount)
                .containsExactly(1);
    }

    /** A user who is on the live board but not among the results being finalized. */
    private void seedLiveScoreboard() {
        liveScoreboard.recordJudgement(
                777L,
                CONTEST_ID,
                10L,
                BYSTANDER_USER_ID,
                contestStart,
                LocalDateTime.of(2025, 9, 19, 10, 0),
                SubmissionResult.ACCEPTED
        );
        assertThat(liveScoreboard.currentRanking(CONTEST_ID)).hasSize(1);
    }

    private record ArgumentCaptorHelper(List<ContestFinalScore> scores) {
        @SuppressWarnings("unchecked")
        static ArgumentCaptorHelper capture(ContestFinalScoreRepository repository) {
            org.mockito.ArgumentCaptor<List<ContestFinalScore>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            org.mockito.Mockito.verify(repository).saveAll(captor.capture());
            return new ArgumentCaptorHelper(captor.getValue());
        }
    }
}
