package my.oj.web.contest.scoreboard;

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

    /**
     * The value of {@code expectedCheckpointFloor} that skips the checkpoint check: a rebuild batch,
     * and a stream batch from a consumer that has not yet observed any checkpoint.
     */
    long NO_CHECKPOINT_FLOOR = -1L;

    Long apply(ApplyRequest request);

    /**
     * Applies a batch in request order and stops at the first failure, with no checkpoint check.
     *
     * @see #applyAll(List, long)
     */
    default List<ApplyResult> applyAll(List<ApplyRequest> requests) {
        return applyAll(requests, NO_CHECKPOINT_FLOOR);
    }

    /**
     * Applies a batch in request order and stops at the first failure. A stream checkpoint must
     * never jump over one bad event and make it unreachable on restart, so nothing after the first
     * failed request is applied - which is why the batch is not a plain pipeline, where Redis keeps
     * executing the commands that follow a failed one.
     *
     * <p>{@code expectedCheckpointFloor} is the checkpoint the caller has already observed or written
     * for the consumer position it is applying from. When it is not {@link #NO_CHECKPOINT_FLOOR} and
     * the stored checkpoint is below it, the store has rolled back underneath the caller: nothing at
     * all is written and the only result is {@link ApplyStatus#ROLLBACK}. The check and the first
     * write happen in one atomic step of the store, so a rollback that lands between the caller's own
     * reading of the checkpoint and this call cannot be stepped over.</p>
     *
     * @return one result per request that was attempted, in request order; the list ends at the
     *         first {@link ApplyStatus#FAILED} or {@link ApplyStatus#ROLLBACK} result
     */
    List<ApplyResult> applyAll(List<ApplyRequest> requests, long expectedCheckpointFloor);

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

    /** How a thrown failure is reported in an {@link ApplyResult}. */
    static String errorMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * One scoreboard write, and the checkpoint claim that goes with it.
     *
     * <p>The claim is not optional for a stream request. A caller that has an offset to store must
     * say what it verified about the step it is asking for, and the script refuses the request when
     * it does not - see {@link CheckpointAdvance}.</p>
     */
    record ApplyRequest(long correlationId,
                        Long streamOffset,
                        CheckpointAdvance advance,
                        ContestScoreboardUpdate update) {
        public ApplyRequest {
            if (update == null) {
                throw new IllegalArgumentException("Scoreboard update is required");
            }
            if (advance == null) {
                throw new IllegalArgumentException("Scoreboard checkpoint advance is required");
            }
            if (streamOffset != null && streamOffset < 0) {
                throw new IllegalArgumentException("Scoreboard stream offset must not be negative");
            }
            if (streamOffset == null && advance != CheckpointAdvance.NONE) {
                throw new IllegalArgumentException(
                        "A request that carries no stream offset cannot advance the checkpoint");
            }
            if (streamOffset != null && advance == CheckpointAdvance.NONE) {
                throw new IllegalArgumentException(
                        "A stream request must classify the checkpoint advance it asks for");
            }
        }

        /** An ordinary forward step in a stream whose position the consumer has already verified. */
        public static ApplyRequest stream(long offset, ContestScoreboardUpdate update) {
            return stream(offset, update, CheckpointAdvance.CONTINUE);
        }

        /** A forward step whose classification the caller decided, which is how a gap is crossed. */
        public static ApplyRequest stream(long offset,
                                          ContestScoreboardUpdate update,
                                          CheckpointAdvance advance) {
            return new ApplyRequest(offset, offset, advance, update);
        }

        public static ApplyRequest rebuild(long correlationId, ContestScoreboardUpdate update) {
            return new ApplyRequest(correlationId, null, CheckpointAdvance.NONE, update);
        }
    }

    /** What one request of a batch did. */
    enum ApplyStatus {
        /** The request changed the scoreboard: a submission it had not processed before. */
        APPLIED,
        /**
         * Nothing new reached the standings: the offset was at or below the checkpoint, or the
         * submission had already been processed. A duplicate stream request above the checkpoint
         * still moves it, exactly as the single-event script does.
         */
        DUPLICATE,
        /** The request was refused or failed; nothing after it in the batch was attempted. */
        FAILED,
        /**
         * The stored checkpoint was below the caller's expected floor, so the store rolled back
         * underneath the caller. Nothing in the batch (or in this chunk of it) was written.
         */
        ROLLBACK
    }

    /**
     * @param appliedOffset the stored checkpoint after the request, which is what {@link #apply}
     *                      returns; for {@link ApplyStatus#ROLLBACK} the checkpoint that was found
     * @param sequence      the recovery sequence the request was issued, or {@code null}
     */
    record ApplyResult(long correlationId,
                       Long appliedOffset,
                       String errorMessage,
                       ApplyStatus status,
                       Long sequence) {

        public ApplyResult {
            if (status == null) {
                status = errorMessage == null ? ApplyStatus.APPLIED : ApplyStatus.FAILED;
            }
        }

        public ApplyResult(long correlationId, Long appliedOffset, String errorMessage) {
            this(correlationId, appliedOffset, errorMessage, null, null);
        }

        public static ApplyResult success(long correlationId, Long appliedOffset) {
            return applied(correlationId, appliedOffset, null);
        }

        public static ApplyResult applied(long correlationId, Long appliedOffset, Long sequence) {
            return new ApplyResult(correlationId, appliedOffset, null, ApplyStatus.APPLIED, sequence);
        }

        public static ApplyResult duplicate(long correlationId, Long appliedOffset) {
            return new ApplyResult(correlationId, appliedOffset, null, ApplyStatus.DUPLICATE, null);
        }

        public static ApplyResult failure(long correlationId, String errorMessage) {
            return new ApplyResult(correlationId, null,
                    errorMessage == null ? "unknown failure" : errorMessage, ApplyStatus.FAILED, null);
        }

        public static ApplyResult rollback(long correlationId, long storedCheckpoint) {
            return new ApplyResult(correlationId, storedCheckpoint, null, ApplyStatus.ROLLBACK, null);
        }

        /** Applied or absorbed as a duplicate: the batch may continue past it. */
        public boolean succeeded() {
            return status == ApplyStatus.APPLIED || status == ApplyStatus.DUPLICATE;
        }

        public boolean newlyApplied() {
            return status == ApplyStatus.APPLIED;
        }

        public boolean rolledBack() {
            return status == ApplyStatus.ROLLBACK;
        }
    }
}
