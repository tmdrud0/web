package my.oj.web.contest.dto;

import my.oj.web.contest.ContestStatus;

import java.time.LocalDateTime;

public record ContestSummaryView(
        Long id,
        String name,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ContestStatus status,
        String statusLabel,
        String timeMessage
) {
}
