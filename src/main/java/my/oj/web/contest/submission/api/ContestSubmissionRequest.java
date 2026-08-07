package my.oj.web.contest.submission.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Same bounds as the form it replaces, so the JSON path cannot accept a submission the page would
 * have rejected.
 */
public record ContestSubmissionRequest(@NotBlank @Size(max = 200_000) String code) {
}
