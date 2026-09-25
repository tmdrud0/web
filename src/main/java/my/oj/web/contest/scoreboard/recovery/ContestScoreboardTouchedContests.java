package my.oj.web.contest.scoreboard.recovery;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which contests this JVM wrote to the scoreboard, and how late - so a rollback is repaired for the
 * contests it can have taken something from rather than for every contest MySQL holds.
 *
 * <h2>The stamp</h2>
 *
 * <p>Every write is stamped with a stream offset. A live event is stamped with its own offset. A
 * replayed chunk carries none, so it is stamped with the checkpoint the scoreboard held when the chunk
 * was applied; that is the latest stream position the chunk's writes can have been captured at by a
 * snapshot, and it compares with a snapshot's checkpoint the same way a live offset does.</p>
 *
 * <p>A rollback restores the standings as they were at a snapshot whose checkpoint is {@code S}. A
 * contest can have lost something only if this JVM wrote to it after the snapshot, and every such write
 * is stamped at or above {@code S}: a live event after the snapshot has an offset above it, and a chunk
 * replayed after the snapshot saw a checkpoint at least as high as the snapshot's. So "stamped at or above
 * {@code S}" can include a contest that lost nothing - a chunk replayed at exactly the snapshot's
 * checkpoint, before it was taken - and cannot leave out one that lost something. Including one too many
 * costs a replay of that contest; leaving one out would leave it short.</p>
 *
 * <h2>What it cannot know</h2>
 *
 * <p>Writes by an earlier JVM. That is why the answer is only used for a range inside what this JVM
 * applied ({@link ContestScoreboardRecoveryStrategy.LostRange#withinAppliedHistory()}), and why a JVM
 * that starts under {@code full-replay} replays every contest before consuming - after which every
 * contest with results is stamped here.</p>
 */
@Component
public class ContestScoreboardTouchedContests {

    private final Map<Long, Long> latestStamp = new ConcurrentHashMap<>();

    /** Records that {@code contestId} was written at stream position {@code stamp}. */
    public void touched(long contestId, long stamp) {
        latestStamp.merge(contestId, stamp, Math::max);
    }

    /**
     * The contests that may have lost writes to a rollback whose restored checkpoint is
     * {@code restoredCheckpoint}, in ascending id order.
     */
    public Set<Long> touchedAtOrAbove(long restoredCheckpoint) {
        Set<Long> contests = new TreeSet<>();
        latestStamp.forEach((contestId, stamp) -> {
            if (stamp >= restoredCheckpoint) {
                contests.add(contestId);
            }
        });
        return contests;
    }
}
