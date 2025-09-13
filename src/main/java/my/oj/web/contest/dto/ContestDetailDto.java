package my.oj.web.contest.dto;

import java.time.LocalDateTime;
import java.util.List;

import my.oj.web.problem.dto.ContestProblemDto;

public record ContestDetailDto(
        Long id,
        String name,
        LocalDateTime startTime,
        LocalDateTime endTime,
        List<ContestProblemDto> problems
) {
    public ContestDetailDto {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public ContestDetailDto(Long id, String name, LocalDateTime startTime, LocalDateTime endTime) {
        this(id, name, startTime, endTime, List.of());
    }

    public ContestDetailDto withProblems(List<ContestProblemDto> problems) {
        return new ContestDetailDto(id, name, startTime, endTime, problems);
    }
}
