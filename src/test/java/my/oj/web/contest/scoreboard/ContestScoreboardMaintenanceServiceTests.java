package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ContestScoreboardMaintenanceServiceTests {

    @Mock
    private ContestScoreboardService scoreboardService;
    @Mock
    private ContestScoreboardOutboxService outboxService;

    @Test
    void clearLiveContestState_resetsProjectionBeforeDeletingPendingUpdates() {
        ContestScoreboardMaintenanceService maintenanceService =
                new ContestScoreboardMaintenanceService(scoreboardService, outboxService);

        maintenanceService.clearLiveContestState(42L);

        InOrder order = inOrder(scoreboardService, outboxService);
        order.verify(scoreboardService).reset(42L);
        order.verify(outboxService).deleteByContestId(42L);
    }
}
