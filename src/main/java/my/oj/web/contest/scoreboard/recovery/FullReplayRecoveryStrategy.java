package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * {@code full-replay}: MySQL holds every stored judgement, so MySQL is the history.
 *
 * <p>The mode never rewinds the consumer. A rollback leaves the checkpoint behind, but the results
 * the rollback took away are still in {@code contest_submission_result} with a
 * {@code scoreboard_applied_at} to say whether the standings ever saw them, so re-sending them is
 * what repairs the standings - and the stream offset plays no part in it.</p>
 *
 * <p>Leaving the consumer alone is not a detail. The judge writes a result to MySQL before it
 * publishes to the stream, so a replay started after the rollback was detected is guaranteed to see
 * every result published before it, and the consumer that was never stopped goes on applying
 * everything published after it. That is the whole argument that no result published during the
 * recovery is missed, and stopping the consumer would throw it away for nothing.</p>
 *
 * <p>A range is therefore always recoverable, whatever the checkpoint says: the only thing that could
 * make it unrecoverable is a result MySQL does not have, and a result reaches the stream only after
 * it reached MySQL. Which is also why the only refusal this mode can produce is the gate's: a replay
 * that threw is retried rather than remembered, because the range it did not cover is untouched by
 * the failure.</p>
 *
 * <h2>Where the replay runs</h2>
 *
 * <p>{@code synchronous} runs the replay on whichever thread asked. The paragraphs above say the consumer
 * is never stopped, but that is exactly what this does: the first live delivery above the rolled-back
 * checkpoint asks, and when it asks first the consumer thread runs the whole replay; when the supervisor
 * asks first, every delivery in the meantime finds the gate held, is refused, and is only re-read by a
 * resubscribe after the replay. Either way nothing new reaches the standings until the replay ends.</p>
 *
 * <p>{@code background} (the default) keeps the paragraphs above true. A rollback is handed to
 * {@link ContestScoreboardBackgroundReplay} and answered {@link Outcome#COVERED} at once, so the live
 * path anchors and keeps applying while the replay runs on its own thread, a chunk at a time under the
 * apply lock. Answering before the replay has run is sound for this basis and no other: MySQL already
 * holds every result in the range, the accepted replay starts after the rollback and is retried until it
 * completes, and a JVM that dies first replays every contest at startup before consuming - which
 * {@link ContestScoreboardRecoveryValidator} makes non-optional whenever this JVM consumes the stream.
 * The live path moving the checkpoint past the range loses nothing, because the range's results are put
 * back from MySQL, not from the stream.</p>
 *
 * <p>The background pass is also narrower and ordered for the tail. It replays only the contests this JVM
 * wrote at or above the restored checkpoint ({@link ContestScoreboardTouchedContests}) when the range lies
 * inside what this JVM applied, and every contest otherwise; and it walks each contest newest first, so
 * the results the rollback took - the newest ones - are back after the first chunk rather than the last.
 * The scoreboard's rules are commutative over arrival order, so neither changes what the standings end
 * up as.</p>
 */
@Slf4j
class FullReplayRecoveryStrategy implements ContestScoreboardRecoveryStrategy, AutoCloseable {

    private final ContestScoreboardFullReplayService replayService;
    private final ContestScoreboardRecoveryPassGate gate;
    private final ContestScoreboardTouchedContests touchedContests;
    /** Null for the synchronous replay. */
    private final ContestScoreboardBackgroundReplay background;

    FullReplayRecoveryStrategy(ContestScoreboardFullReplayService replayService,
                               ContestScoreboardRecoveryPassGate gate) {
        this(replayService, gate, new ContestScoreboardTouchedContests(), null);
    }

    FullReplayRecoveryStrategy(ContestScoreboardFullReplayService replayService,
                               ContestScoreboardRecoveryPassGate gate,
                               ContestScoreboardTouchedContests touchedContests,
                               ContestScoreboardBackgroundReplay background) {
        this.replayService = replayService;
        this.gate = gate;
        this.touchedContests = touchedContests;
        this.background = background;
    }

    @Override
    public ContestScoreboardRecoveryMode mode() {
        return ContestScoreboardRecoveryMode.FULL_REPLAY;
    }

    /**
     * True: the consumer waits for the startup replay.
     *
     * <p>Not because a result would be lost otherwise - the paragraph above is about a rollback
     * observed on a running JVM, and it still holds there: this mode never stops the consumer once it
     * is consuming. A cold start is a different question. The consumer's start sits at the end of the
     * context refresh and this mode's replay is an {@code ApplicationRunner}, so without a boundary the
     * consumer would begin re-reading the stream from the stored checkpoint first, and the history the
     * restored scoreboard was missing would be put back through the stream. This mode's basis would
     * then be replaying MySQL over a scoreboard that no longer needed it, and nothing in the log would
     * say the recovery was not the one the operator selected.</p>
     *
     * <p>Waiting costs nothing that is not recoverable. The checkpoint does not move while the replay
     * runs - a rebuild request carries no stream offset - so the consumer resumes at exactly the
     * checkpoint it would have used, and everything published during the hold is above it.</p>
     */
    @Override
    public boolean recoversHistoryBeforeConsuming() {
        return true;
    }

    @Override
    public boolean rewindsOnCheckpointRegression() {
        return false;
    }

    @Override
    public Outcome rebuildHistory(LostRange range) {
        if (background != null) {
            // Not asked of rebuiltAlready(). The lifecycle marks a range rebuilt when the answer is
            // COVERED, and in the background that answer means "a pass is queued", not "a pass has
            // finished". A second rollback with nothing applied in between states the same top as the
            // first, so the mark would skip it while the first pass may already have walked past what it
            // took away. Every question goes to the background replay, which answers it with a pass that
            // starts after it.
            return requestInBackground(range);
        }
        if (range.rebuiltAlready()) {
            return Outcome.COVERED;
        }
        try {
            return gate.tryRun(PassKind.MYSQL_REPLAY, () -> {
                replayService.replayAllContests();
                return Outcome.COVERED;
            }).orElse(Outcome.BUSY_RETRY_LATER);
        } catch (RuntimeException failure) {
            // Answered rather than thrown: the range is exactly as recoverable as it was before the
            // attempt, and an exception that reached the caller would leave the supervisor with no
            // way to say so - its own catch records nothing, and the rollback would be forgotten.
            log.error("The MySQL basis could not replay the scoreboard history the rollback took away "
                    + "between offsets {} and {}; the replay is retried on the next supervisor cycle",
                    range.firstLostOffset(), range.lastLostOffset(), failure);
            return Outcome.RETRYABLE_FAILURE;
        }
    }

    private Outcome requestInBackground(LostRange range) {
        // Inside what this JVM applied, the contests it wrote at or above the restored checkpoint are
        // every contest the rollback can have taken something from. Outside it - offsets this JVM never
        // applied - any contest could be involved, so every contest is replayed.
        Set<Long> contests = null;
        if (range.withinAppliedHistory()) {
            Set<Long> touched = touchedContests.touchedAtOrAbove(range.checkpointOffset());
            contests = touched.isEmpty() ? null : touched;
        }
        if (!background.request(range.checkpointOffset(), range.highestAppliedOffset(), contests)) {
            log.warn("The background scoreboard replay is closed; the rollback between offsets {} and {} is "
                    + "asked about again", range.firstLostOffset(), range.lastLostOffset());
            return Outcome.RETRYABLE_FAILURE;
        }
        return Outcome.COVERED;
    }

    @Override
    public void close() {
        if (background != null) {
            background.close();
        }
    }
}
