package my.oj.web.contest.scoreboard.outbox;

/**
 * An outbox row reduced to what lost-tail recovery compares against the Redis allocator.
 */
public record SequencedOutboxRow(Long id, Long redisSequence) {
}
