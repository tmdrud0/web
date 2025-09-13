package my.oj.web.contest.dto;

import java.time.LocalDateTime;

public record ContestDto(Long id, String name, LocalDateTime startTime, LocalDateTime endTime) {
}
