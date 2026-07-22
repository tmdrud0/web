package my.oj.web.contest.scoreboard.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "contest.outbox.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContestScoreboardOutboxScheduler {

    private final ContestScoreboardOutboxProcessor processor;
    private final ContestScoreboardOutboxRecoveryService recoveryService;
    private final ContestScoreboardOutboxProcessLock processLock;
    private final ContestScoreboardOutboxProperties properties;

    public ContestScoreboardOutboxScheduler(ContestScoreboardOutboxProcessor processor,
                                            ContestScoreboardOutboxRecoveryService recoveryService,
                                            ContestScoreboardOutboxProcessLock processLock,
                                            ContestScoreboardOutboxProperties properties) {
        this.processor = processor;
        this.recoveryService = recoveryService;
        this.processLock = processLock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${contest.outbox.poll-interval-ms:5000}")
    public void pollAndProcess() {
        processLock.executeIfAcquired(() -> processor.processBatch(
                        properties.effectiveBatchSize(),
                        properties.claimTimeout()
                ))
                .filter(result -> result.stale() > 0)
                .ifPresent(result -> log.debug(
                        "Ignored {} stale scoreboard outbox completion results",
                        result.stale()
                ));
    }

    @Scheduled(fixedDelayString = "${contest.outbox.recovery-interval-ms:30000}")
    public void recoverRedisState() {
        recoveryService.requeueDuplicateSequences(properties.effectiveRecoveryBatchSize());
        recoveryService.requeueLostTail(properties.effectiveRecoveryBatchSize());
    }
}
