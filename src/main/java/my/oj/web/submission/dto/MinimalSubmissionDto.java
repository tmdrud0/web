package my.oj.web.submission.dto;

import java.time.LocalDateTime;

import my.oj.web.submission.SubmissionResult;

public record MinimalSubmissionDto(Long id, SubmissionResult result, LocalDateTime submittedTime) {
}
