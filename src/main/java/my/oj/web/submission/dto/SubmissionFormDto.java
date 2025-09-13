package my.oj.web.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmissionFormDto(
        @NotBlank @Size(max = 200_000)
        String code
) {}