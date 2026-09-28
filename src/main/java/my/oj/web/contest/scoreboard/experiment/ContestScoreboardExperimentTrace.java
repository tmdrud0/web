package my.oj.web.contest.scoreboard.experiment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Timing records for the live-impact experiment: when a live batch reached the scoreboard, and when a
 * recovery pass and its chunks ran, on which thread.
 *
 * <p>Off by default. The only production implementation that records anything is created under
 * {@code contest.scoreboard.experiment.trace.enabled=true}; everywhere else the callers hold
 * {@link #NOOP}, and they ask {@link #enabled()} before building a record so a disabled trace costs one
 * field read on the paths it observes. Recording never throws and never blocks: a record that cannot be
 * queued is counted and dropped, because the paths being measured must not be slowed or failed by the
 * instrument measuring them.</p>
 *
 * <p>Every instant is {@link System#currentTimeMillis()} in the recording JVM. In the experiment stack
 * that is the container clock, which is the frame the out-of-process poller also reads.</p>
 */
public interface ContestScoreboardExperimentTrace {

    /** The trace every caller holds unless the experiment turned tracing on. Records nothing. */
    ContestScoreboardExperimentTrace NOOP = new ContestScoreboardExperimentTrace() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void liveBatchApplied(long appliedAtEpochMillis, List<LiveEvent> events) {
        }

        @Override
        public void recovery(RecoveryRecord record) {
        }
    };

    /** Whether records are kept at all. Callers skip building a record when this is false. */
    boolean enabled();

    /**
     * One live stream batch that the scoreboard accepted.
     *
     * @param appliedAtEpochMillis the instant the applier answered for every event in the batch, which
     *                             is when the standings reflect them
     */
    void liveBatchApplied(long appliedAtEpochMillis, List<LiveEvent> events);

    /** One recovery-side record: a pass, a chunk of a pass, or a live-path gap question. */
    void recovery(RecoveryRecord record);

    /**
     * One event of an applied live batch.
     *
     * @param judgedAt the judge's own timestamp as the stream message carried it, without a zone
     */
    record LiveEvent(long offset, long submissionId, LocalDateTime judgedAt) {
    }

    /**
     * What a recovery-side record describes.
     *
     * <p>{@code PASS_START} is written when a pass takes the gate, before it has done anything, so a
     * pass that never finishes still leaves its start; {@code PASS_END} carries the same start and its
     * end. {@code PASS_SKIPPED} is an attempt that found the gate held. {@code CHUNK} is one replayed
     * chunk, and its lock instant separates waiting for the apply lock from holding it. {@code GAP} is
     * the live path asking the mode about a range below a delivery.</p>
     *
     * <p>{@code ROLLBACK_DETECTED} is the {@code mysql-poll} delivery's detector finding the Redis allocator
     * below the MySQL watermark: written once the range is persisted and the allocator fenced. Its detail is
     * the range, its outcome the check that found it ({@code startup}, {@code periodic}, {@code poll-batch},
     * {@code script-refusal}, {@code recovery-refusal}). Under that delivery a range recovery is a
     * {@code PASS_START}/{@code CHUNK}/{@code PASS_END} pass like any other.</p>
     */
    enum RecoveryEvent {
        PASS_START,
        PASS_END,
        PASS_SKIPPED,
        CHUNK,
        GAP,
        ROLLBACK_DETECTED
    }

    /**
     * @param lockedAtEpochMillis the instant the apply lock was acquired, or {@code -1} where no lock
     *                            is involved
     * @param rows                results in the chunk, or {@code -1} where the record has none
     * @param detail              the pass kind, the chunk's description, or the gap's reason
     * @param outcome             what the step came to: a pass value or failure, a gap's answer
     */
    record RecoveryRecord(RecoveryEvent event,
                          String thread,
                          long startEpochMillis,
                          long lockedAtEpochMillis,
                          long endEpochMillis,
                          int rows,
                          String detail,
                          String outcome) {
    }
}
