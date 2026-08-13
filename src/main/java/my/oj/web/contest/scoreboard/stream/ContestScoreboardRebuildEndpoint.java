package my.oj.web.contest.scoreboard.stream;

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
class ContestScoreboardRebuildEndpoint {

    private final ContestScoreboardRebuildService rebuildService;
    private final ContestScoreboardStreamProcessingLock processingLock;

    ContestScoreboardRebuildEndpoint(
            ContestScoreboardRebuildService rebuildService,
            ContestScoreboardStreamProcessingLock processingLock
    ) {
        this.rebuildService = rebuildService;
        this.processingLock = processingLock;
    }

    @WriteOperation
    Map<String, Object> rebuild(long contestId) {
        processingLock.withLock(() -> rebuildService.rebuildFromContestResults(contestId));
        return Map.of("contestId", contestId, "status", "rebuilt");
    }
}
