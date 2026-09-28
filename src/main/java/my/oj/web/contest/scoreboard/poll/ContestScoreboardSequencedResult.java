package my.oj.web.contest.scoreboard.poll;

import my.oj.web.contest.scoreboard.ContestScoreboardUpdate;
import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

/**
 * One judged result as the MySQL ledger holds it, with the Redis sequence it was last applied under.
 *
 * @param appliedSequence {@code scoreboard_applied_seq}, or {@code null} when no application was recorded
 */
public record ContestScoreboardSequencedResult(long submissionId,
                                               Long appliedSequence,
                                               long contestId,
                                               long problemId,
                                               long userId,
                                               LocalDateTime contestStart,
                                               LocalDateTime submittedTime,
                                               SubmissionResult result) {

    ContestScoreboardUpdate toUpdate() {
        return new ContestScoreboardUpdate(submissionId, contestId, problemId, userId,
                contestStart, submittedTime, result, null);
    }
}
