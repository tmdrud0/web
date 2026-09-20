package my.oj.web.contest.scoreboard.recovery;

/**
 * Decides how the scoreboard recovers a Redis state that was rolled back to an RDB snapshot.
 *
 * <p>The mode never changes how judging results travel - the RabbitMQ stream and the single Lua
 * write path stay in place. It changes which checkpoint decides what has to be replayed, and how a
 * lost tail is detected.</p>
 */
public enum ContestScoreboardRecoveryMode {

    /** Trusts the stream offset stored beside the scoreboard in the same Redis snapshot. */
    STREAM_OFFSET("stream-offset"),

    /** Replays every judged result of the contest from MySQL without resetting Redis. */
    FULL_REPLAY("full-replay"),

    /** Detects a reused sequence number and replays only the results the tail lost. */
    REDIS_SEQ("redis-seq");

    private final String propertyValue;

    ContestScoreboardRecoveryMode(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    /** The spelling accepted by {@code contest.scoreboard.recovery.mode}. */
    public String propertyValue() {
        return propertyValue;
    }
}
