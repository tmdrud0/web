package my.oj.web.contest.submission.core;

/**
 * A sequence number the scoreboard handed out more than once.
 *
 * <p>Reuse is what a rollback leaves behind: the scoreboard's allocator rewinds with the snapshot,
 * so results applied after the snapshot are re-applied under sequence numbers that stored results
 * already hold. The count is carried because the group is the evidence - one row at a sequence is
 * normal, and two rows at one sequence is the whole detection.
 */
public interface ContestScoreboardDuplicateSequence {

    Long getAppliedSequence();

    long getResultCount();
}
