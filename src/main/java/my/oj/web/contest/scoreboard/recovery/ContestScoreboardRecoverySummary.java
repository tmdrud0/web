package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.delivery.ContestScoreboardDelivery;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardMySqlPollProperties;

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
     * @param store    the effective {@code contest.scoreboard.store} value
     * @param delivery the effective {@code contest.scoreboard.delivery}
     * @param poll     the {@code mysql-poll} settings, read only for that delivery
     * @return a single line naming the mode, its delivery and every setting that mode reads
     */
    public static String describe(ContestScoreboardRecoveryMode mode,
                                  String store,
                                  ContestScoreboardDelivery delivery,
                                  ContestScoreboardRecoveryProperties properties,
                                  ContestScoreboardMySqlPollProperties poll) {
        StringBuilder summary = new StringBuilder()
                .append("mode=").append(mode.propertyValue())
                .append(" store=").append(store)
                .append(" recovery-owner=").append(properties.owner().enabled())
                .append(" delivery=").append(delivery.propertyValue());
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
            // redis-seq is delivered and recovered by the MySQL poller; the mysql-poll settings are the
            // ones it runs with. The legacy contest.scoreboard.recovery.redis-seq.* settings are not read.
            case REDIS_SEQ -> summary.append(" batch-size=").append(poll.batchSize())
                    .append(" poll-interval=").append(duration(poll.pollInterval()))
                    .append(" rollback-check-interval=").append(duration(poll.rollbackCheckInterval()))
                    .append(" recovery-interval=").append(duration(poll.recoveryInterval()))
                    .append(" recovery-chunk-size=").append(poll.recoveryChunkSize())
                    .append(" recovery-max-iterations=").append(poll.recoveryMaxIterations());
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
