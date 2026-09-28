package my.oj.web.contest.scoreboard.poll;

import java.util.List;
import java.util.Map;

/**
 * The MySQL side of the {@code mysql-poll} delivery: the judged results, the sequence each was applied
 * under, the durable watermark, and the pending recovery ranges. MySQL is the ledger; Redis is derived.
 */
public interface ContestScoreboardSequenceLedger {

    /** {@code highest_durable_seq}: the highest sequence MySQL has recorded against a result. */
    long highestDurableSequence();

    /** The highest judged submission id with no recorded sequence, or {@code null} when there is none. */
    Long highestUnsequencedJudgedSubmissionId();

    /**
     * Judged results with no recorded sequence, {@code afterId < submission_id <= throughId}, ascending.
     *
     * @param afterId {@code null} for the first page
     */
    List<ContestScoreboardSequencedResult> unsequencedJudgedResults(Long afterId, long throughId, int limit);

    /**
     * Records the sequence each result was applied under and raises the watermark to the highest of them,
     * in one transaction: when any marker fails, the watermark does not move either.
     */
    void recordApplied(Map<Long, Long> sequencesBySubmissionId);

    /** Pending ranges, oldest generation first. */
    List<ContestScoreboardRecoveryRange> pendingRanges();

    /**
     * Persists a pending range, or returns the identical pending one already persisted - a fence that
     * failed after the range was written is retried without writing the range twice.
     */
    ContestScoreboardRecoveryRange openRange(long fromExclusive, long throughInclusive);

    /** The first judged results whose sequence is inside the range, by sequence then submission id. */
    List<ContestScoreboardSequencedResult> judgedResultsInRange(long fromExclusive, long throughInclusive, int limit);

    /**
     * Marks one generation completed, compared against the generation and its pending status.
     *
     * @return whether this call completed it
     */
    boolean completeRange(long generation);
}
