package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.rebuild.ContestScoreboardRebuildService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
@Slf4j
class ContestScoreboardStreamRecoveryService {

    private final ContestScoreboardRebuildService rebuildService;
    private final ContestScoreboardStreamMetrics metrics;

    ContestScoreboardStreamRecoveryService(
            ContestScoreboardRebuildService rebuildService,
            ContestScoreboardStreamMetrics metrics
    ) {
        this.rebuildService = rebuildService;
        this.metrics = metrics;
    }

    void recoverRetentionGap(long expectedOffset, long firstAvailableOffset) {
        metrics.recordOffsetGap();
        log.error(
                "Scoreboard stream offset {} is no longer retained; rebuilding all contests before resuming at {}",
                expectedOffset,
                firstAvailableOffset
        );
        int rebuilt = rebuildService.rebuildAllFromContestResults();
        log.warn("Rebuilt {} contests after scoreboard stream retention gap", rebuilt);
    }
}
