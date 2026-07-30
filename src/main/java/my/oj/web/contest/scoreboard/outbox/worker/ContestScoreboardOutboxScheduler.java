package my.oj.web.contest.scoreboard.outbox.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the scoreboard outbox. Several instances may run this at the same time: claiming uses
 * {@code FOR UPDATE SKIP LOCKED} with a lease token so no row is handed out twice, and applying
 * a judgement to the scoreboard is order-independent, so the interleaving does not matter.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "contest.outbox.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContestScoreboardOutboxScheduler {

    private final ContestScoreboardOutboxProcessor processor;
    private final ContestScoreboardOutboxRecoveryService recoveryService;
    private final ContestScoreboardOutboxProperties properties;

    public ContestScoreboardOutboxScheduler(ContestScoreboardOutboxProcessor processor,
                                            ContestScoreboardOutboxRecoveryService recoveryService,
                                            ContestScoreboardOutboxProperties properties) {
        this.processor = processor;
        this.recoveryService = recoveryService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${contest.outbox.poll-interval-ms:5000}")
    public void pollAndProcess() {
        ContestScoreboardOutboxProcessor.BatchProcessResult result = processor.processBatch(
                properties.effectiveBatchSize(),
                properties.claimTimeout()
        );
        if (result.stale() > 0) {
            log.debug("Ignored {} stale scoreboard outbox completion results", result.stale());
        }
    }

    @Scheduled(fixedDelayString = "${contest.outbox.recovery-interval-ms:30000}")
    public void recoverRedisState() {
        recoveryService.requeueDuplicateSequences(properties.effectiveRecoveryBatchSize());
        recoveryService.requeueLostTail(properties.effectiveRecoveryBatchSize());
    }
}
