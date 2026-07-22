package my.oj.web.contest.submission.judge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.core.ContestSubmissionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "contest.submission.judge.scheduler", name = "enabled", havingValue = "true")
public class ContestSubmissionJudgeScheduler {

    private final ContestSubmissionRepository contestSubmissionRepository;
    private final ContestSubmissionJudgeProcessor processor;
    @Qualifier("contestSubmissionExecutor")
    private final ThreadPoolTaskExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean polling = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${contest.submission.judge.poll-interval-ms:1000}")
    public void pollAndQueue() {
        pollAndQueueInternal();
    }

    private void pollAndQueueInternal() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        try {
            int batchSize = availableCapacity();
            if (batchSize <= 0) {
                return;
            }

            List<Long> ids = contestSubmissionRepository.findTopUnjudgedSubmissionIds(batchSize);
            for (Long id : ids) {
                if (id == null || !inFlight.add(id)) {
                    continue;
                }
                executor.execute(() -> {
                    try {
                        processor.judge(id);
                    } catch (RuntimeException ex) {
                        log.warn("Failed to judge contest submission {}", id, ex);
                    } finally {
                        inFlight.remove(id);
                        if (hasIdleCapacity()) {
                            pollAndQueueInternal();
                        }
                    }
                });
            }
        } finally {
            polling.set(false);
        }
    }

    private boolean hasIdleCapacity() {
        return availableCapacity() > 0;
    }

    private int availableCapacity() {
        int maxConcurrency = Math.max(1, executor.getMaxPoolSize());
        return Math.max(0, maxConcurrency - inFlight.size());
    }

    int inFlightCount() {
        return inFlight.size();
    }

    boolean isPolling() {
        return polling.get();
    }

    void triggerImmediatePollForTest() {
        pollAndQueueInternal();
    }
}
