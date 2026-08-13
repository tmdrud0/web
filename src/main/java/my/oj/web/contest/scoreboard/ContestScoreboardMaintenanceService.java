package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContestScoreboardMaintenanceService {

    private final ContestScoreboardApplier scoreboardApplier;
    public void clearLiveContestState(Long contestId) {
        scoreboardApplier.reset(contestId);
    }
}
