package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.delivery.RabbitStreamDeliveryCondition;
import org.springframework.context.annotation.Conditional;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.rebuild.ContestScoreboardRebuildService;
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
@Conditional(RabbitStreamDeliveryCondition.class)
class ContestScoreboardRebuildEndpoint {

    private final ContestScoreboardRebuildService rebuildService;
    private final ContestScoreboardApplyLock applyLock;

    ContestScoreboardRebuildEndpoint(
            ContestScoreboardRebuildService rebuildService,
            ContestScoreboardApplyLock applyLock
    ) {
        this.rebuildService = rebuildService;
        this.applyLock = applyLock;
    }

    @WriteOperation
    Map<String, Object> rebuild(long contestId) {
        applyLock.withLock(() -> rebuildService.rebuildFromContestResults(contestId));
        return Map.of("contestId", contestId, "status", "rebuilt");
    }
}
