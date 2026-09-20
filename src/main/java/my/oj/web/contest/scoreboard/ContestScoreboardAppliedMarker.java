package my.oj.web.contest.scoreboard;

import lombok.RequiredArgsConstructor;
import my.oj.web.contest.scoreboard.stream.JdbcContestScoreboardAppliedAtWriter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one place that records "these results reached the scoreboard" in MySQL.
 *
 * <p>Three paths apply results - the live stream, the operator rebuild and a recovery replay - and
 * all three must persist the same evidence. Putting the mode decision here rather than at each call
 * site means a path cannot quietly record a different set of columns from its siblings.
 *
 * <p>When the mode issues sequences, the sequence is read back from the scoreboard for the whole
 * batch in one lookup, and a result the scoreboard never sequenced keeps whatever the column
 * already held.
 */
@Component
@RequiredArgsConstructor
public class ContestScoreboardAppliedMarker {

    private final JdbcContestScoreboardAppliedAtWriter writer;
    private final ContestScoreboardSequenceSource sequenceSource;
    private final ContestScoreboardSequenceTracking sequenceTracking;

    /** Records the staleness timestamp, and the recovery sequence when the mode issues one. */
    public void markApplied(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return;
        }
        if (!sequenceTracking.enabled()) {
            writer.markApplied(submissionIds);
            return;
        }
        writer.markApplied(submissionIds, sequenceSource.appliedSequences(submissionIds));
    }
}
