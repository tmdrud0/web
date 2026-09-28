package my.oj.web.contest.scoreboard.poll;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;

/**
 * The Redis side of the {@code mysql-poll} delivery: one atomic apply that issues the sequence, the
 * allocator it issues from, and the fence that moves that allocator forward after a rollback.
 */
public interface ContestScoreboardSequencedApplier {

    /** Returned by {@link #apply} when the allocator is below the expected watermark. */
    long ROLLBACK = -1L;

    /**
     * Applies one judged result and returns the sequence it now holds, or {@link #ROLLBACK} having changed
     * nothing when the allocator is below {@code expectedWatermark}.
     *
     * @param resequenceFloor an already-processed result keeps its mapped sequence only when that is above
     *                        this value; otherwise it is issued a fresh one without being scored again
     */
    long apply(ContestScoreboardUpdate update, long expectedWatermark, long resequenceFloor);

    /** The highest sequence issued, {@code 0} when none. */
    long allocatorSequence();

    /** Atomically raises the allocator to at least {@code atLeast}; returns the resulting allocator. */
    long fenceAllocator(long atLeast);
}
