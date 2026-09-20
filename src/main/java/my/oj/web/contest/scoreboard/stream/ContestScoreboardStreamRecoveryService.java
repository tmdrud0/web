package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardFullReplayService;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * What happens when the offsets the scoreboard needs cannot be read from the stream any more.
 *
 * <p>The offsets between the checkpoint and the offset the consumer was handed are unreachable, so the
 * results they carried exist only in MySQL. Replaying from MySQL is what puts them back, and the
 * replay is deliberately non-destructive: it re-sends every stored judgement through the same apply
 * path the live stream uses and lets the processed set absorb what is already there, rather than
 * resetting the standings the RDB snapshot restored.</p>
 *
 * <p>The fallback is switchable because replaying every contest is a large operation and an operator
 * may want to look before it runs. {@code none} does not silently skip the lost results: it reports
 * the gap as unbridged, so the live path refuses the batch and the consumer keeps failing loudly
 * until someone acts.</p>
 *
 * <p>Reached only through {@code StreamOffsetRecoveryStrategy}. The other two modes rebuild their
 * history from MySQL or from the sequence, and giving them this service would be exactly the
 * blurring that keeps the three from being comparable.</p>
 *
 * <p>The gap itself is counted where it is observed - by {@code ContestScoreboardStreamProcessor},
 * which is the only thing that sees a delivery jump past the checkpoint - rather than here, so the
 * counter does not depend on which mode was asked to bridge it.</p>
 */
@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
@Slf4j
public class ContestScoreboardStreamRecoveryService {

    private final ContestScoreboardFullReplayService fullReplayService;
    private final ContestScoreboardRecoveryProperties properties;

    ContestScoreboardStreamRecoveryService(
            ContestScoreboardFullReplayService fullReplayService,
            ContestScoreboardRecoveryProperties properties
    ) {
        this.fullReplayService = fullReplayService;
        this.properties = properties;
    }

    /**
     * Handles a range the stream could not serve below the offset the consumer was handed.
     *
     * <p>Both ends of the range are reported as they were observed. Neither is derived by arithmetic:
     * the offsets a stream holds are whatever was published, so nothing here may describe the gap as
     * an offset that was never seen - which is what asserting the checkpoint's successor would do.</p>
     *
     * @param checkpointOffset the offset Redis holds and the stream could not serve
     * @param lastLostOffset   the highest offset whose result the standings may be missing, which is
     *                         the one just below the delivery that jumped the checkpoint
     * @return whether the gap may now be bridged, which is what decides if the delivery may move the
     *         checkpoint past the missing range
     */
    public boolean recoverRetentionGap(long checkpointOffset, long lastLostOffset) {
        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback fallback =
                properties.streamOffset().retentionGapFallback();
        log.error(
                "Scoreboard stream offset {} could not be served to the consumer; the results carried by "
                        + "offsets {} to {} are missing from the standings",
                checkpointOffset,
                checkpointOffset + 1L,
                lastLostOffset
        );

        if (fallback == ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.NONE) {
            log.error(
                    "contest.scoreboard.recovery.stream-offset.retention-gap-fallback=none: the results carried by "
                            + "offsets {} to {} will not be replayed from MySQL. The batch is left unapplied rather "
                            + "than bridging the gap, so the checkpoint does not move past results the scoreboard "
                            + "never saw. Replay the scoreboard from MySQL or reset it, or set the fallback to "
                            + "full-replay, before resuming.",
                    checkpointOffset + 1L,
                    lastLostOffset
            );
            return false;
        }

        int replayed = fullReplayService.replayAllContests();
        log.warn(
                "Replayed {} stored results from MySQL after the scoreboard stream could not serve offsets {} to {}",
                replayed,
                checkpointOffset + 1L,
                lastLostOffset
        );
        return true;
    }
}
