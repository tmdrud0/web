package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.rebuild.ContestScoreboardRebuildService;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardTouchedContests;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Endpoint(id = "contestscoreboard")
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
class ContestScoreboardRebuildEndpoint {

    private final ContestScoreboardRebuildService rebuildService;
    private final ContestScoreboardApplyLock applyLock;
    private final ContestScoreboardApplier applier;
    private final ContestScoreboardTouchedContests touchedContests;

    ContestScoreboardRebuildEndpoint(
            ContestScoreboardRebuildService rebuildService,
            ContestScoreboardApplyLock applyLock,
            ContestScoreboardApplier applier,
            ContestScoreboardTouchedContests touchedContests
    ) {
        this.rebuildService = rebuildService;
        this.applyLock = applyLock;
        this.applier = applier;
        this.touchedContests = touchedContests;
    }

    @WriteOperation
    Map<String, Object> rebuild(long contestId) {
        applyLock.withLock(() -> {
            rebuildService.rebuildFromContestResults(contestId);
            // A rebuild rewrites the contest, so a rollback to a snapshot taken before it has to repair it.
            touchedContests.touched(contestId, applier.currentStreamOffset());
        });
        return Map.of("contestId", contestId, "status", "rebuilt");
    }
}
