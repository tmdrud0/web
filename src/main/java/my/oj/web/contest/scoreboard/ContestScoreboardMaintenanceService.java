package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContestScoreboardMaintenanceService {

    private final ContestScoreboardService scoreboardService;
    private final ContestScoreboardOutboxService outboxService;

    public void clearLiveContestState(Long contestId) {
        scoreboardService.reset(contestId);
        outboxService.deleteByContestId(contestId);
    }
}
