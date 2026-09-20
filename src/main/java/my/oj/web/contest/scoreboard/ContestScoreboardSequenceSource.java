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
}
