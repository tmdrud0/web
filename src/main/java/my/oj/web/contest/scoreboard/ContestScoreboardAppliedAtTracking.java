package my.oj.web.contest.scoreboard;

import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryMode;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * Whether the live stream path records {@code scoreboard_applied_at} in MySQL, and the Redis db-pending
 * set that repairs it ({@code contest.scoreboard.stream-offset.applied-at-tracking}).
 *
 * <p>The column is a staleness timestamp, not a checkpoint. {@code stream-offset} recovers from the
 * offset stored beside the standings and never reads it, so in that mode the per-batch MySQL
 * {@code UPDATE}, the {@code SADD}/{@code SREM} on the db-pending set, and the ACK that waited for both
 * are cost with no reader. {@code full-replay} and {@code redis-seq} do use the column (and, in
 * {@code redis-seq}, the sequence written beside it) as the ledger their recovery reads, so they may not
 * turn it off - {@code ContestScoreboardRecoveryValidator} refuses that at startup.</p>
 *
 * <p>Unset, the setting follows the mode: off in {@code stream-offset}, on everywhere else. An explicit
 * value always wins, which is how the measurement harness keeps earlier runs comparable by starting
 * {@code stream-offset} with it on.</p>
 *
 * <p>Only the live stream path is switched. The operator rebuild and the recovery replays still mark what
 * they applied - they run rarely, and a rebuilt contest keeps a column that says it was rebuilt.</p>
 */
@FunctionalInterface
public interface ContestScoreboardAppliedAtTracking {

    String PROPERTY = "contest.scoreboard.stream-offset.applied-at-tracking";

    ContestScoreboardAppliedAtTracking ENABLED = () -> true;
    ContestScoreboardAppliedAtTracking DISABLED = () -> false;

    boolean enabled();

    /** The value the operator wrote, if any, bound the way every other boolean property is. */
    static Optional<Boolean> configured(Environment environment) {
        if (environment == null) {
            return Optional.empty();
        }
        return Binder.get(environment).bind(PROPERTY, Boolean.class).map(Optional::of).orElse(Optional.empty());
    }

    /**
     * The effective setting: the configured value, or the mode's default when none is configured.
     *
     * @param mode the bound recovery mode, or {@code null} when no recovery properties exist (a slice
     *             context), which keeps the historical behaviour of tracking
     */
    static boolean resolve(Optional<Boolean> configured, ContestScoreboardRecoveryMode mode) {
        return configured.orElse(mode != ContestScoreboardRecoveryMode.STREAM_OFFSET);
    }
}
