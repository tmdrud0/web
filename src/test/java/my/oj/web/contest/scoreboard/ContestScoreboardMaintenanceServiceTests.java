package my.oj.web.contest.scoreboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardMaintenanceServiceTests {

    @Mock
    private ContestScoreboardApplier scoreboardApplier;
    @Test
    void clearLiveContestState_resetsProjection() {
        ContestScoreboardMaintenanceService maintenanceService =
                new ContestScoreboardMaintenanceService(scoreboardApplier);

        maintenanceService.clearLiveContestState(42L);

        verify(scoreboardApplier).reset(42L);
    }
}
