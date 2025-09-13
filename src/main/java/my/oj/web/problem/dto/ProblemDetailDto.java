package my.oj.web.problem.dto;

import java.util.List;

import my.oj.web.submission.dto.MinimalSubmissionDto;

public record ProblemDetailDto(
        Long id,
        String name,
        Long contestId,
        String contestName,
        Long contestNum,
        List<MinimalSubmissionDto> userSubmissions
) {
    public ProblemDetailDto {
        userSubmissions = userSubmissions == null ? List.of() : List.copyOf(userSubmissions);
    }
}
