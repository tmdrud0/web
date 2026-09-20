package my.oj.web.contest.scoreboard.recovery;

import java.time.Duration;

/**
 * Renders the selected recovery mode and the settings that mode actually runs with.
 *
 * <p>Kept as a pure function so the startup report is asserted directly, instead of being read back
 * out of a log appender.</p>
 */
public final class ContestScoreboardRecoverySummary {

    private ContestScoreboardRecoverySummary() {
    }

    /**
     * @param store the effective {@code contest.scoreboard.store} value
     * @return a single line naming the mode and every setting that mode reads
     */
    public static String describe(ContestScoreboardRecoveryMode mode,
                                  String store,
                                  ContestScoreboardRecoveryProperties properties) {
        StringBuilder summary = new StringBuilder()
                .append("mode=").append(mode.propertyValue())
                .append(" store=").append(store)
                .append(" recovery-owner=").append(properties.owner().enabled());
        switch (mode) {
            case STREAM_OFFSET -> {
                ContestScoreboardRecoveryProperties.StreamOffset streamOffset = properties.streamOffset();
                summary.append(" startup-offset=").append(streamOffset.startupOffset().propertyValue())
                        .append(" retention-gap-fallback=")
                        .append(streamOffset.retentionGapFallback().propertyValue());
            }
            case FULL_REPLAY -> {
                ContestScoreboardRecoveryProperties.FullReplay fullReplay = properties.fullReplay();
                summary.append(" db-batch-size=").append(fullReplay.dbBatchSize())
                        .append(" replay-batch-size=").append(fullReplay.replayBatchSize())
                        .append(" startup-replay-enabled=").append(fullReplay.startupReplayEnabled());
            }
            case REDIS_SEQ -> {
                ContestScoreboardRecoveryProperties.RedisSequence redisSeq = properties.redisSeq();
                summary.append(" duplicate-check-interval=").append(duration(redisSeq.duplicateCheckInterval()))
                        .append(" lost-tail-check-interval=").append(duration(redisSeq.lostTailCheckInterval()))
                        .append(" check-window-size=").append(redisSeq.checkWindowSize())
                        .append(" max-windows-per-pass=").append(redisSeq.maxWindowsPerPass())
                        .append(" max-iterations=").append(redisSeq.maxIterations())
                        .append(" replay-batch-size=").append(redisSeq.replayBatchSize())
                        .append(" retry-max-attempts=").append(redisSeq.retryMaxAttempts())
                        .append(" retry-backoff=").append(duration(redisSeq.retryBackoff()))
                        .append(" startup-check-enabled=").append(redisSeq.startupCheckEnabled());
            }
        }
        return summary.toString();
    }

    private static String duration(Duration duration) {
        if (duration == null) {
            return "unset";
        }
        long millis = duration.toMillis();
        return millis % 1_000L == 0L ? (millis / 1_000L) + "s" : millis + "ms";
    }
}
