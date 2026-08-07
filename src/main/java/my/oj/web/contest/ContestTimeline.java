package my.oj.web.contest;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How long until a contest starts, or ends, or how long ago it finished.
 *
 * <p>Rendered server-side because it is the same sentence for every caller and the rule for which
 * sentence applies is the same rule {@link ContestStatus} already encodes. A client is free to
 * recompute it from {@code startTime} and {@code endTime}, which are in the response too.
 */
public final class ContestTimeline {

    private ContestTimeline() {
    }

    public static String message(ContestStatus status, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return switch (status) {
            case UPCOMING -> {
                if (start == null) {
                    yield "Start time not set";
                }
                yield "Starts in " + formatDuration(Duration.between(now, start));
            }
            case RUNNING -> {
                if (end == null) {
                    yield "In progress";
                }
                yield "Time left " + formatDuration(Duration.between(now, end));
            }
            case ENDED -> end == null
                    ? "Finished"
                    : "Finished (" + end.toString().replace("T", " ") + ")";
        };
    }

    private static String formatDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "00:00:00";
        }
        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86_400;
        long hours = (totalSeconds % 86_400) / 3_600;
        long minutes = (totalSeconds % 3_600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        builder.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        return builder.toString();
    }
}
