package my.oj.web.contest.submission.core;

/**
 * A stored judgement together with the sequence the scoreboard recorded for it.
 *
 * <p>Sequence recovery walks and orders by that sequence rather than by submission id, so it needs
 * the column in the projection: paging a lost tail by id would restart from the wrong end of the
 * scoreboard's history on every window.
 */
public interface ContestScoreboardSequencedRow extends ContestScoreboardReplayRow {

    Long getAppliedSequence();
}
