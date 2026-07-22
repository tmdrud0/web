package my.oj.web.user.rank.streak;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "rank.streak.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StreakRankBatchScheduler {

    private final StreakRankBatchService batchService;

    @Scheduled(cron = "${rank.streak.batch.cron:0 30 4 * * *}")
    public void runDailyBatch() {
        try {
            batchService.rebuildForYesterday();
        } catch (Exception ex) {
            log.error("Failed to rebuild streak snapshot", ex);
        }
    }
}
