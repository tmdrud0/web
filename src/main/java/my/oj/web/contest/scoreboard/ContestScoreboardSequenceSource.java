package my.oj.web.contest.scoreboard;

import java.util.Collection;
import java.util.Map;

/**
 * Reads back the recovery sequence the scoreboard holds for a submission.
 *
 * <p>This is the second half of the sequence contract: the write path issues the sequence while it
 * mutates the standings, and the JDBC completion reads it here to persist it on the judging result.
 * Reading it back rather than threading it through the apply reply keeps the reply - and therefore
 * the stream path - identical to what it was before sequences existed.
 */
public interface ContestScoreboardSequenceSource {

    /** Sequences held for these submissions, omitting any the scoreboard never sequenced. */
    Map<Long, Long> appliedSequences(Collection<Long> submissionIds);

    /**
     * The highest sequence the scoreboard has issued, or {@code 0} when it has issued none.
     *
     * <p>Every stored sequence was issued from here, so no stored sequence can be above it while the
     * two are consistent. That is what makes the comparison usable as a rollback test - and also why
     * it only means anything when the stored sequences were read first: a sequence reaches MySQL
     * after this value issued it, so a row read before this read and still ahead of it can only mean
     * this value moved backwards.</p>
     *
     * <p>A scoreboard that has issued nothing reads as zero rather than as an error, because an empty
     * sequence state is exactly what a restore of a snapshot taken before sequencing began leaves
     * behind.</p>
     */
    long allocatorSequence();

    /**
     * How many submissions the scoreboard has sequenced.
     *
     * <p>Observed rather than bounded: the mapping grows with every distinct submission ever
     * applied, and nothing here prunes it. Reporting the size is what makes the growth a decision an
     * operator can take later instead of a surprise.
     */
    long mappedSubmissionCount();
}
