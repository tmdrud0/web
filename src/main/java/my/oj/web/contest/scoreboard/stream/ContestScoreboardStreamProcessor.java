package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
class ContestScoreboardStreamProcessor {

    private final ContestScoreboardApplier applier;
    private final ContestScoreboardAppliedAtCompletion completion;
    private final ContestScoreboardStreamRecoveryService recoveryService;
    private final ContestScoreboardStreamMetrics metrics;
    private final ContestScoreboardStreamProcessingLock processingLock;

    ContestScoreboardStreamProcessor(
            ContestScoreboardApplier applier,
            ContestScoreboardAppliedAtCompletion completion,
            ContestScoreboardStreamRecoveryService recoveryService,
            ContestScoreboardStreamMetrics metrics,
            ContestScoreboardStreamProcessingLock processingLock
    ) {
        this.applier = applier;
        this.completion = completion;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.processingLock = processingLock;
    }

    long process(List<ContestScoreboardStreamEvent> events) {
        return processingLock.withLock(() -> processLocked(events));
    }

    private long processLocked(List<ContestScoreboardStreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return applier.currentStreamOffset();
        }

        long startingOffset = applier.currentStreamOffset();
        ContestScoreboardStreamEvent firstNew = events.stream()
                .filter(event -> event.offset() > startingOffset)
                .findFirst()
                .orElse(null);
        boolean recoveredGap = firstNew != null && firstNew.offset() > startingOffset + 1L;
        if (recoveredGap) {
            recoveryService.recoverRetentionGap(startingOffset + 1L, firstNew.offset());
        }

        List<ContestScoreboardApplier.ApplyRequest> requests = new ArrayList<>(events.size());
        for (ContestScoreboardStreamEvent event : events) {
            if (recoveredGap && event.offset() == firstNew.offset()) {
                requests.add(ContestScoreboardApplier.ApplyRequest.streamAfterRebuild(
                        event.offset(),
                        event.update()
                ));
            } else {
                requests.add(ContestScoreboardApplier.ApplyRequest.stream(event.offset(), event.update()));
            }
        }

        List<ContestScoreboardApplier.ApplyResult> results = applier.applyAll(requests);
        ContestScoreboardApplier.ApplyResult failed = results.stream()
                .filter(result -> !result.succeeded())
                .findFirst()
                .orElse(null);
        if (failed != null || results.size() != requests.size()) {
            String detail = failed == null ? "batch stopped before every event was applied" : failed.errorMessage();
            throw new IllegalStateException("Failed to apply scoreboard stream batch: " + detail);
        }

        completion.complete(events.stream().map(event -> event.message().submissionId()).toList());
        long appliedOffset = applier.currentStreamOffset();
        // Count offsets only after MySQL completion. The metric's own watermark deliberately
        // lags Redis when a batch fails halfway, so the successful retry counts those earlier
        // Lua writes once; a delivery repeated after ACK loss counts zero.
        metrics.recordApplied(events.stream().map(ContestScoreboardStreamEvent::offset).toList(), appliedOffset);
        return appliedOffset;
    }
}
