package my.oj.web.contest.scoreboard;

/**
 * What a caller is asking the scoreboard to do with the stream checkpoint.
 *
 * <p>This replaces a {@code boolean allowOffsetGap}. The boolean described the caller's arithmetic -
 * "yes, there is a hole here" - and said nothing about why the hole was allowed to be crossed, which
 * is the only thing that makes crossing it safe. Every value here is instead a claim the caller has
 * verified, and the script refuses a stream request that carries no claim at all: a caller that
 * forgets to classify its step fails loudly rather than moving the checkpoint in silence.</p>
 *
 * <h2>Why offsets are not contiguous</h2>
 *
 * <p>A RabbitMQ stream offset identifies one entry in one stream. It is not a counter this
 * application controls, and nothing promises the offsets a consumer sees are consecutive - a
 * different producer, an expiry policy, or simply the broker's own numbering can hand over 5, then 7,
 * then 12. Treating {@code previous + 1} as the only acceptable successor rejects those streams for
 * being sparse, which is what the removed continuity check did.</p>
 *
 * <p>What replaces it is a single question, asked once per consumer position: has the range below the
 * offset being applied been rebuilt? While the answer is yes for the position the consumer is in, the
 * offsets it delivers are applied in order and only monotonicity is meaningful. Only when the
 * consumer is <em>handed</em> an offset the checkpoint cannot reach does anything have to decide
 * whether a range was skipped, and that decision belongs to the recovery mode - see
 * {@code ContestScoreboardRecoveryStrategy}.</p>
 */
public enum CheckpointAdvance {

    /**
     * No checkpoint movement: a rebuild request carries no stream offset.
     */
    NONE(""),

    /**
     * An ordinary forward step in a stream whose position the consumer has already verified.
     */
    CONTINUE("continue"),

    /**
     * The first step after the range below this offset was rebuilt, so the checkpoint may be set to
     * it even though the offset it held is not this offset's predecessor.
     */
    ANCHOR("anchor");

    private final String token;

    CheckpointAdvance(String token) {
        this.token = token;
    }

    /**
     * The value the script reads as {@code ARGV[2]}.
     *
     * <p>{@link #CONTINUE} and {@link #ANCHOR} travel as different tokens so the caller's own
     * classification reaches the logs and the script's errors. The script permits both: it cannot
     * tell a legitimate sparse step from an anchor step, because offsets are not contiguous - the
     * distinction is real to the caller, which is where the verification happened, and not to the
     * script, which only ever sees one offset.</p>
     */
    public String token() {
        return token;
    }
}
