package my.oj.web.contest.scoreboard.poll;

/**
 * Whether this JVM may poll and recover right now.
 *
 * <p>The deployment runs one poller - the {@code batch-role} instance with
 * {@code contest.scoreboard.recovery.owner.enabled=true} - and every lock in the poll path is JVM-local.
 * This is the runtime guard behind that rule, not a failover protocol: a second owner that cannot take
 * ownership does nothing and says so.</p>
 */
public interface ContestScoreboardPollOwnership {

    ContestScoreboardPollOwnership ALWAYS = new ContestScoreboardPollOwnership() {
        @Override
        public boolean holds() {
            return true;
        }

        @Override
        public void release() {
        }
    };

    /** Takes ownership if nobody holds it, and confirms it is still held when this JVM does. */
    boolean holds();

    void release();
}
