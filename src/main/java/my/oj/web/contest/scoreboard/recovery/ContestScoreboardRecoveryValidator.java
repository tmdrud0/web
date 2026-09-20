package my.oj.web.contest.scoreboard.recovery;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Rejects a recovery mode that cannot work with the selected scoreboard store.
 *
 * <p>{@code redis-seq} checkpoints through sequence numbers the Redis write path issues, so it has
 * nothing to read when the scoreboard is kept in memory. Failing here keeps the mismatch from
 * turning into a mode that starts cleanly and then never detects anything.</p>
 */
@Component
public class ContestScoreboardRecoveryValidator implements SmartInitializingSingleton {

    private final ContestScoreboardRecoveryProperties properties;
    private final Environment environment;

    public ContestScoreboardRecoveryValidator(ContestScoreboardRecoveryProperties properties,
                                              Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String store = ContestScoreboardStoreProperty.value(environment);
        if (properties.mode() == ContestScoreboardRecoveryMode.REDIS_SEQ && !"redis".equals(store)) {
            throw new IllegalStateException(
                    "contest.scoreboard.recovery.mode=redis-seq requires contest.scoreboard.store=redis"
                            + " but the configured store is '" + store + "'"
            );
        }
    }
}
