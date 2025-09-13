package my.oj.web.submission.dto;

public record SubmitSubmissionCommand(
        Long userId,
        Long problemId,
        String code
) {}