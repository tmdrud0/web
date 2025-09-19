package my.oj.web.contest;

import java.time.LocalDateTime;

public enum ContestStatus {
    UPCOMING("Upcoming"),
    RUNNING("Running"),
    ENDED("Finished");

    private final String label;

    ContestStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ContestStatus from(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (start != null && now.isBefore(start)) {
            return UPCOMING;
        }
        if (end != null && now.isAfter(end)) {
            return ENDED;
        }
        return RUNNING;
    }
}

