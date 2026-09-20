package my.oj.web.contest.submission.core;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

/**
 * One stored judgement, shaped for a scoreboard replay.
 *
 * <p>Only the columns the scoreboard update is built from. The rebuild path hydrates
 * {@link ContestSubmission} entities and their problem, user and contest associations to read the
 * same values, which costs a join per row and pulls columns nothing here looks at. A replay that
 * walks a whole contest is exactly where that difference is worth the projection.</p>
 */
public interface ContestScoreboardReplayRow {

    Long getSubmissionId();

    Long getContestId();

    Long getProblemId();

    Long getUserId();

    LocalDateTime getContestStart();

    LocalDateTime getSubmittedTime();

    /** The result to apply: the final one when the contest has been finalized, else the provisional. */
    SubmissionResult getResult();
}
