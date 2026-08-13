package my.oj.web.contest.scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * The only write path into the live scoreboard. The RabbitMQ Stream consumer drives it in steady
 * state and a rebuild drives it after {@link #reset(long)}, so the scoring rules live in one
 * place per backing store.
 *
 * <p>Deduplication keys off {@link ContestScoreboardUpdate#contestSubmissionId()}. A live request
 * also carries the broker-assigned stream offset. Redis implementations persist that offset in
 * the same Lua invocation that mutates the scoreboard. Rebuild requests deliberately carry no
 * offset: rebuilding one contest must never move the global stream checkpoint.
 */
public interface ContestScoreboardApplier {

    Long apply(ApplyRequest request);

    /**
     * Applies a batch in request order and stops at the first failure. A stream checkpoint must
     * never jump over one bad event and make it unreachable on restart.
     */
    default List<ApplyResult> applyAll(List<ApplyRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ApplyRequest> safeRequests = List.copyOf(requests);
        List<ApplyResult> results = new ArrayList<>(safeRequests.size());
        for (ApplyRequest request : safeRequests) {
            try {
                results.add(ApplyResult.success(request.correlationId(), apply(request)));
            } catch (RuntimeException exception) {
                results.add(ApplyResult.failure(request.correlationId(), errorMessage(exception)));
                break;
            }
        }
        return List.copyOf(results);
    }

    /**
     * Highest RabbitMQ Stream offset atomically reflected in this scoreboard, or {@code -1} when
     * no live stream event has been applied. Redis rollback rewinds this value with the state.
     */
    long currentStreamOffset();

    /**
     * Clears one contest's standings and processed-submission set. The global stream offset
     * survives: a contest rebuild repairs derived state but does not claim unrelated stream work.
     */
    void reset(long contestId);

    private static String errorMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    record ApplyRequest(long correlationId,
                        Long streamOffset,
                        boolean allowOffsetGap,
                        ContestScoreboardUpdate update) {
        public ApplyRequest {
            if (update == null) {
                throw new IllegalArgumentException("Scoreboard update is required");
            }
            if (streamOffset != null && streamOffset < 0) {
                throw new IllegalArgumentException("Scoreboard stream offset must not be negative");
            }
            if (allowOffsetGap && streamOffset == null) {
                throw new IllegalArgumentException("Only a stream request can allow an offset gap");
            }
        }

        public static ApplyRequest stream(long offset, ContestScoreboardUpdate update) {
            return new ApplyRequest(offset, offset, false, update);
        }

        public static ApplyRequest streamAfterRebuild(long offset, ContestScoreboardUpdate update) {
            return new ApplyRequest(offset, offset, true, update);
        }

        public static ApplyRequest rebuild(long correlationId, ContestScoreboardUpdate update) {
            return new ApplyRequest(correlationId, null, false, update);
        }
    }

    record ApplyResult(long correlationId, Long appliedOffset, String errorMessage) {

        public static ApplyResult success(long correlationId, Long appliedOffset) {
            return new ApplyResult(correlationId, appliedOffset, null);
        }

        public static ApplyResult failure(long correlationId, String errorMessage) {
            return new ApplyResult(correlationId, null, errorMessage);
        }

        public boolean succeeded() {
            return errorMessage == null;
        }
    }
}
