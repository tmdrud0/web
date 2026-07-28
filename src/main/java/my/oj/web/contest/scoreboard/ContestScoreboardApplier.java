package my.oj.web.contest.scoreboard;

import java.util.List;

/**
 * The only write path into the live scoreboard. The outbox worker drives it in steady state
 * and a rebuild drives it after {@link #reset(long)}, so the scoring rules live in one place
 * per backing store.
 *
 * <p>Deduplication keys off {@link ContestScoreboardUpdate#contestSubmissionId()}. The
 * {@code eventId} carried by {@link ApplyRequest} is a caller-side correlation token — it maps
 * a result back to the outbox row that produced it and has no effect on what gets applied.
 */
public interface ContestScoreboardApplier {

    /**
     * Applies one judgement and returns the sequence assigned to its submission. The sequence
     * is allocated once per submission and stays stable across retries and rebuilds.
     */
    Long apply(Long eventId, ContestScoreboardUpdate update);

    /**
     * Applies a batch, returning one result per request in the same order. Implementations may
     * apply the batch in one round trip; the default falls back to applying one at a time so a
     * single bad event cannot fail the rest.
     */
    default List<ApplyResult> applyAll(List<ApplyRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return List.copyOf(requests).stream()
                .map(request -> {
                    try {
                        return ApplyResult.success(
                                request.eventId(),
                                apply(request.eventId(), request.update())
                        );
                    } catch (RuntimeException exception) {
                        return ApplyResult.failure(request.eventId(), errorMessage(exception));
                    }
                })
                .toList();
    }

    /**
     * Highest sequence handed out so far. Recovery compares it against the sequences recorded
     * in the outbox to spot a store that lost data and needs those rows replayed.
     */
    long currentSequence();

    /**
     * Clears one contest's standings. Sequences survive, so a rebuild re-applies the same
     * judgements onto empty standings without renumbering them.
     */
    void reset(long contestId);

    private static String errorMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    record ApplyRequest(long eventId, ContestScoreboardUpdate update) {
        public ApplyRequest {
            if (update == null) {
                throw new IllegalArgumentException("Scoreboard update is required");
            }
        }
    }

    record ApplyResult(long eventId, Long sequence, String errorMessage) {

        public static ApplyResult success(long eventId, Long sequence) {
            return new ApplyResult(eventId, sequence, null);
        }

        public static ApplyResult failure(long eventId, String errorMessage) {
            return new ApplyResult(eventId, null, errorMessage);
        }

        public boolean succeeded() {
            return errorMessage == null;
        }
    }
}
