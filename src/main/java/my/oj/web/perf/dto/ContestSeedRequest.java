package my.oj.web.perf.dto;

import java.time.LocalDateTime;

public record ContestSeedRequest(
        String prefix,
        Integer userCount,
        Integer problemCount,
        Integer durationMinutes,
        LocalDateTime startTime,
        Boolean reset
) {

    public String resolvedPrefix() {
        return (prefix == null || prefix.isBlank()) ? "perf" : prefix.trim();
    }

    public int resolvedUserCount() {
        return userCount == null ? 10_000 : Math.max(userCount, 1);
    }

    public int resolvedProblemCount() {
        return problemCount == null ? 5 : Math.max(problemCount, 1);
    }

    public int resolvedDurationMinutes() {
        return durationMinutes == null ? 120 : Math.max(durationMinutes, 1);
    }

    public LocalDateTime resolvedStartTime() {
        return startTime == null ? LocalDateTime.now().minusMinutes(1) : startTime;
    }

    public boolean shouldReset() {
        return reset == null || reset;
    }
}

