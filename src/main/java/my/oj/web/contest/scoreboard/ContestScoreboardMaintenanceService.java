package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContestScoreboardMaintenanceService {

    private final ContestScoreboardApplier scoreboardApplier;
    private final ContestScoreboardOutboxService outboxService;

    public void clearLiveContestState(Long contestId) {
        scoreboardApplier.reset(contestId);
        outboxService.deleteByContestId(contestId);
    }
}
