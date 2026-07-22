package my.oj.web.perf.dto;

import my.oj.web.submission.SubmissionOrigin;

public record ContestSubmissionPerfResult(Long submissionId,
                                          SubmissionOrigin origin,
                                          boolean duplicate,
                                          double elapsedMillis) {
}

