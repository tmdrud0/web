package my.oj.web.contest.scoreboard;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

/**
 * Immutable input for applying one contest judgement to the live scoreboard.
 */
public record ContestScoreboardUpdate(
        Long contestSubmissionId,
        Long contestId,
        Long problemId,
        Long userId,
        LocalDateTime contestStart,
        LocalDateTime submittedTime,
        SubmissionResult result,
        LocalDateTime judgedAt
) {
}
