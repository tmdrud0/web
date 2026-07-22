package my.oj.web.perf.dto;

public record ContestSubmissionRequest(Long userId,
                                       Long problemId,
                                       String code) {
}

