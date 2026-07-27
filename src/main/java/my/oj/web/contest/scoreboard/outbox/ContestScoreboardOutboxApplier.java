package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

import java.util.List;

public interface ContestScoreboardOutboxApplier {

    Long apply(Long eventId, ContestScoreboardUpdate update);

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

    long currentSequence();

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

    record ApplyResult(long eventId, Long redisSequence, String errorMessage) {

        public static ApplyResult success(long eventId, Long redisSequence) {
            return new ApplyResult(eventId, redisSequence, null);
        }

        public static ApplyResult failure(long eventId, String errorMessage) {
            return new ApplyResult(eventId, null, errorMessage);
        }

        public boolean succeeded() {
            return errorMessage == null;
        }
    }
}
