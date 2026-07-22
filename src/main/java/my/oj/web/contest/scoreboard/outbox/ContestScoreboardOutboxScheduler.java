package my.oj.web.contest.scoreboard.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "contest.outbox.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContestScoreboardOutboxScheduler {

    private final ContestScoreboardOutboxProcessor processor;
    private final ContestScoreboardOutboxRecoveryService recoveryService;
    private final ContestScoreboardOutboxProcessLock processLock;
    @Value("${contest.outbox.batch-size:50}")
    private int batchSize;
    @Value("${contest.outbox.recovery-batch-size:50}")
    private int recoveryBatchSize;
    @Value("${contest.outbox.claim-timeout:30s}")
    private String claimTimeout;

    @Scheduled(fixedDelayString = "${contest.outbox.poll-interval-ms:5000}")
    public void pollAndProcess() {
        processLock.executeIfAcquired(() -> processor.processBatch(
                        Math.max(1, batchSize),
                        DurationStyle.detectAndParse(claimTimeout)
                ))
                .filter(result -> result.stale() > 0)
                .ifPresent(result -> log.debug(
                        "Ignored {} stale scoreboard outbox completion results",
                        result.stale()
                ));
    }

    @Scheduled(fixedDelayString = "${contest.outbox.recovery-interval-ms:30000}")
    public void recoverRedisState() {
        recoveryService.requeueDuplicateSequences(Math.max(1, recoveryBatchSize));
        recoveryService.requeueLostTail(Math.max(1, recoveryBatchSize));
    }
}
