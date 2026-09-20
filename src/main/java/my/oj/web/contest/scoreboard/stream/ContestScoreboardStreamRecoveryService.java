package my.oj.web.contest.scoreboard.stream;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardFullReplayService;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryProperties;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * What happens when the offset the scoreboard needs is no longer in the broker's retention.
 *
 * <p>The offsets between the checkpoint and the earliest retained message are gone for good, so the
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
     * Handles a gap between the stored checkpoint and the earliest offset still retained.
     *
     * @return whether the gap may now be bridged, which is what decides if the event at
     *         {@code firstAvailableOffset} is allowed to move the checkpoint past the missing range
     */
    public boolean recoverRetentionGap(long expectedOffset, long firstAvailableOffset) {
        ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback fallback =
                properties.streamOffset().retentionGapFallback();
        log.error(
                "Scoreboard stream offset {} is no longer retained; the earliest offset the broker still has is {}",
                expectedOffset,
                firstAvailableOffset
        );

        if (fallback == ContestScoreboardRecoveryProperties.StreamOffset.RetentionGapFallback.NONE) {
            log.error(
                    "contest.scoreboard.recovery.stream-offset.retention-gap-fallback=none: the results carried by "
                            + "offsets {} to {} are gone from the broker and will not be replayed from MySQL. The "
                            + "batch is left unapplied rather than bridging the gap, so the checkpoint does not move "
                            + "past results the scoreboard never saw. Replay the scoreboard from MySQL or reset it, "
                            + "or set the fallback to full-replay, before resuming.",
                    expectedOffset,
                    firstAvailableOffset - 1L
            );
            return false;
        }

        int replayed = fullReplayService.replayAllContests();
        log.warn(
                "Replayed {} stored results from MySQL after the scoreboard stream retention gap; resuming at {}",
                replayed,
                firstAvailableOffset
        );
        return true;
    }
}
