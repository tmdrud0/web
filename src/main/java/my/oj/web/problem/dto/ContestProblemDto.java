package my.oj.web.problem.dto;

public record ContestProblemDto(Long id, String name, Long contestNum, Long solvedNum) {
    public ContestProblemDto(Long id, String name, Long contestNum) {
        this(id, name, contestNum, 0L);
    }
}
