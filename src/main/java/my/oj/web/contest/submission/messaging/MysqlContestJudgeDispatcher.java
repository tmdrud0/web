package my.oj.web.contest.submission.messaging;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct MySQL claimant used only for the transport trade-off experiment. */
@Slf4j
@Component
@ConditionalOnExpression(
        "'${contest.submission.judge.dispatch-mode:rabbit}' == 'mysql' && "
                + "'${contest.submission.judge.rabbit.listener.enabled:false}' == 'true'")
class MysqlContestJudgeDispatcher {

    private final ContestJudgeOutboxStore outboxStore;
    private final ContestSubmissionJudgeProcessor judgeProcessor;
    private final MysqlContestJudgeProperties properties;
    private final MysqlContestJudgeMetrics metrics;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger reserved = new AtomicInteger();

    MysqlContestJudgeDispatcher(ContestJudgeOutboxStore outboxStore,
                                ContestSubmissionJudgeProcessor judgeProcessor,
                                MysqlContestJudgeProperties properties,
                                MysqlContestJudgeMetrics metrics) {
        this.outboxStore = outboxStore;
        this.judgeProcessor = judgeProcessor;
        this.properties = properties;
        this.metrics = metrics;
        int workers = properties.effectiveWorkerCount();
        int queueCapacity = Math.max(1, properties.effectiveMaxInFlight() - workers);
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "contest-mysql-judge-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        metrics.bindExecutor(executor::getActiveCount, executor.getQueue()::size, reserved::get);
    }

    @Scheduled(fixedDelayString = "${contest.submission.judge.mysql.poll-interval:100ms}")
    void poll() {
        int capacity = properties.effectiveMaxInFlight() - reserved.get();
        int claimSize = Math.min(properties.effectiveClaimBatchSize(), capacity);
        if (claimSize <= 0) {
            return;
        }

        long started = System.nanoTime();
        List<ContestJudgeOutboxStore.ClaimedEvent> events =
                outboxStore.claim(claimSize, properties.effectiveClaimTimeout());
        metrics.recordClaim(
                System.nanoTime() - started,
                events.size(),
                (int) events.stream().filter(ContestJudgeOutboxStore.ClaimedEvent::staleReclaim).count());
        for (ContestJudgeOutboxStore.ClaimedEvent event : events) {
            reserved.incrementAndGet();
            try {
                executor.execute(() -> judge(event));
            } catch (RejectedExecutionException exception) {
                reserved.decrementAndGet();
                metrics.recordRejection();
                fail(event, "Local judge executor rejected claimed work");
                log.warn("Rejected claimed contest judge event {}", event.eventId(), exception);
            }
        }
    }

    private void judge(ContestJudgeOutboxStore.ClaimedEvent event) {
        try {
            judgeProcessor.judge(event.submissionId());
            ContestJudgeOutboxStore.BatchCompletionResult result =
                    outboxStore.completeAll(List.of(event), List.of());
            metrics.recordCompletion("success", result.publishedApplied());
            metrics.recordCompletion("stale", result.staleCount());
        } catch (RuntimeException failure) {
            fail(event, failureMessage(failure));
            log.warn("Direct MySQL judging failed for event {}", event.eventId(), failure);
        } finally {
            reserved.decrementAndGet();
        }
    }

    private void fail(ContestJudgeOutboxStore.ClaimedEvent event, String error) {
        ContestJudgeOutboxStore.BatchCompletionResult result = outboxStore.completeAll(
                List.of(), List.of(new ContestJudgeOutboxStore.FailedEvent(event, error)));
        metrics.recordCompletion("failure", result.failedApplied());
        metrics.recordCompletion("stale", result.staleCount());
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    int reservedCount() {
        return reserved.get();
    }
}
