package my.oj.web.contest.scoreboard.outbox;

import my.oj.web.submission.SubmissionResult;

import java.time.LocalDateTime;

public record ContestScoreboardOutboxPayload(
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

