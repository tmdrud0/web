package my.oj.web.contest.dto;

import java.time.LocalDateTime;

public record ContestDetailDto(
        Long id,
        String name,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime finalizedAt
) {

    public ContestDetailDto(Long id, String name, LocalDateTime startTime, LocalDateTime endTime) {
        this(id, name, startTime, endTime, null);
    }

    public boolean finalized() {
        return finalizedAt != null;
    }
}
