package my.oj.web.contest.scoreboard.stream;

/**
 * Finishes the MySQL half of every live application the Redis scoreboard still lists as pending.
 *
 * <p>The set it drains ({@code contest:scoreboard:stream:db-pending}) is written by the apply script in
 * the same step as the standings, and emptied by the live batch once {@code scoreboard_applied_at} is
 * written. The consumer drains what is left whenever it (re)starts. A mode that answers a rollback
 * without restarting the consumer has to drain it itself, because a Redis rollback restores the set to
 * its snapshot value - including ids the live path had already completed and removed.</p>
 *
 * <p>Idempotent and safe to repeat: an id is removed from the set only after the timestamp write for
 * that id, and the write keeps the first timestamp.</p>
 */
public interface ContestScoreboardAppliedAtRepair {

    void repairPending();
}
