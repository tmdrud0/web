package my.oj.web.contest.submission.messaging;

public record ContestJudgeMessage(long eventId, long submissionId, int schemaVersion) {
}
