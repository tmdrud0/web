package my.oj.web.contest.scoreboard.outbox.worker;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ContestScoreboardOutboxProcessor {

    private final ContestScoreboardApplier scoreboardApplier;
    private final JdbcContestScoreboardOutboxQueue outboxQueue;

    public BatchProcessResult processBatch(int batchSize, Duration claimLease) {
        List<JdbcContestScoreboardOutboxQueue.ClaimedEvent> claimed = outboxQueue.claim(batchSize, claimLease);
        if (claimed.isEmpty()) {
            return BatchProcessResult.empty();
        }

        List<ContestScoreboardApplier.ApplyRequest> requests = claimed.stream()
                .map(event -> new ContestScoreboardApplier.ApplyRequest(
                        event.eventId(),
                        event.update()
                ))
                .toList();
        List<ContestScoreboardApplier.ApplyResult> applyResults;
        try {
            applyResults = scoreboardApplier.applyAll(requests);
            if (applyResults == null) {
                applyResults = List.of();
            }
        } catch (RuntimeException exception) {
            String error = exceptionMessage(exception);
            applyResults = requests.stream()
                    .map(request -> ContestScoreboardApplier.ApplyResult.failure(
                            request.eventId(),
                            error
                    ))
                    .toList();
        }

        List<JdbcContestScoreboardOutboxQueue.CompletedEvent> completed = new ArrayList<>(claimed.size());
        List<JdbcContestScoreboardOutboxQueue.FailedEvent> failed = new ArrayList<>();
        for (int index = 0; index < claimed.size(); index++) {
            JdbcContestScoreboardOutboxQueue.ClaimedEvent event = claimed.get(index);
            if (index >= applyResults.size()) {
                failed.add(new JdbcContestScoreboardOutboxQueue.FailedEvent(
                        event,
                        "Scoreboard applier returned fewer results than requested"
                ));
                continue;
            }

            ContestScoreboardApplier.ApplyResult result = applyResults.get(index);
            if (result.eventId() != event.eventId()) {
                failed.add(new JdbcContestScoreboardOutboxQueue.FailedEvent(
                        event,
                        "Scoreboard applier result order did not match the claimed event order"
                ));
            } else if (result.succeeded()) {
                completed.add(new JdbcContestScoreboardOutboxQueue.CompletedEvent(
                        event,
                        result.sequence()
                ));
            } else {
                failed.add(new JdbcContestScoreboardOutboxQueue.FailedEvent(event, result.errorMessage()));
            }
        }

        JdbcContestScoreboardOutboxQueue.BatchCompletionResult completion = outboxQueue.completeAll(completed, failed);
        return new BatchProcessResult(
                claimed.size(),
                completion.completedApplied(),
                completion.failedApplied(),
                completion.staleCount()
        );
    }

    public record BatchProcessResult(int claimed, int completed, int failed, int stale) {

        static BatchProcessResult empty() {
            return new BatchProcessResult(0, 0, 0, 0);
        }
    }

    private static String exceptionMessage(RuntimeException exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

}
