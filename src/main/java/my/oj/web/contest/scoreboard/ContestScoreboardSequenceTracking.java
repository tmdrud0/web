package my.oj.web.contest.scoreboard;

/**
 * Whether the selected scoreboard issues a recovery sequence for each result it applies.
 *
 * <p>The sequence exists to make a Redis rollback detectable, and it is not free: it costs one
 * counter increment and one hash field per applied result, and it is the reason the global mapping
 * hash grows. Modes that checkpoint through the stream offset instead do not need it, so they do
 * not pay for it - which is also what keeps the recovery modes comparable rather than making one
 * of them carry another's bookkeeping.
 *
 * <p>The flag is read from the bound recovery properties rather than from the raw property string.
 * Binding is lenient about case and separators, so the string an operator wrote and the mode the
 * application reports can differ, and the value that decides behaviour should be the one that is
 * reported.
 */
@FunctionalInterface
public interface ContestScoreboardSequenceTracking {

    ContestScoreboardSequenceTracking DISABLED = () -> false;

    boolean enabled();
}
