package my.oj.web.contest.scoreboard.stream;

/**
 * A stream batch the scoreboard refused because its checkpoint was below what this JVM had already
 * observed for the consumer's position - Redis was rolled back underneath the consumer.
 *
 * <p>Not a failed batch. Nothing in it was written, so there is no unapplied range to hold back and no
 * failure to count; what it calls for is the mode's rollback answer, which for {@code stream-offset} is a
 * resubscribe at the stored checkpoint. The listener hands it to
 * the rollback signal for exactly that, instead of waiting for the supervisor's
 * next pass.</p>
 */
class ContestScoreboardCheckpointRegressedException extends IllegalStateException {

    private final long expectedFloor;
    private final long storedCheckpoint;
    private final long consumerGeneration;

    ContestScoreboardCheckpointRegressedException(long expectedFloor, long storedCheckpoint, long consumerGeneration) {
        super("Scoreboard stream checkpoint rolled back to " + storedCheckpoint + " below " + expectedFloor
                + ", which this consumer had already observed; the batch was refused without writing anything");
        this.expectedFloor = expectedFloor;
        this.storedCheckpoint = storedCheckpoint;
        this.consumerGeneration = consumerGeneration;
    }

    long expectedFloor() {
        return expectedFloor;
    }

    long storedCheckpoint() {
        return storedCheckpoint;
    }

    /** The consumer position the refused batch was read from - see {@link ContestScoreboardStreamPosition}. */
    long consumerGeneration() {
        return consumerGeneration;
    }
}
