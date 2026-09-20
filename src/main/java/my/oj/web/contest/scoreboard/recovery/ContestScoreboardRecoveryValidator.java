package my.oj.web.contest.scoreboard.recovery;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Rejects a recovery mode that cannot work with the selected scoreboard store, and a mode written
 * in a spelling the mode's own beans cannot see.
 *
 * <p>{@code redis-seq} checkpoints through sequence numbers the Redis write path issues, so it has
 * nothing to read when the scoreboard is kept in memory. Failing here keeps the mismatch from
 * turning into a mode that starts cleanly and then never detects anything.</p>
 *
 * <p>The spelling check exists because the two halves of mode selection read the property
 * differently: the record is bound leniently, so {@code FULL_REPLAY} binds to
 * {@link ContestScoreboardRecoveryMode#FULL_REPLAY} and the startup report and this validator both
 * call the mode full-replay, while {@code @ConditionalOnProperty} compares the string as written
 * and therefore creates none of the mode's beans. Requiring the canonical spelling - the one the
 * property's own documentation lists, and the one the store axis has always required - means the
 * mode an operator sees reported is always the mode that is running.</p>
 */
@Component
public class ContestScoreboardRecoveryValidator implements SmartInitializingSingleton {

    static final String MODE_PROPERTY = "contest.scoreboard.recovery.mode";

    private final ContestScoreboardRecoveryProperties properties;
    private final Environment environment;

    public ContestScoreboardRecoveryValidator(ContestScoreboardRecoveryProperties properties,
                                              Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        rejectNonCanonicalModeSpelling();
        String store = ContestScoreboardStoreProperty.value(environment);
        if (properties.mode() == ContestScoreboardRecoveryMode.REDIS_SEQ && !"redis".equals(store)) {
            throw new IllegalStateException(
                    "contest.scoreboard.recovery.mode=redis-seq requires contest.scoreboard.store=redis"
                            + " but the configured store is '" + store + "'"
            );
        }
    }

    private void rejectNonCanonicalModeSpelling() {
        String configured = environment.getProperty(MODE_PROPERTY);
        String canonical = properties.mode().propertyValue();
        if (configured != null && !configured.isBlank() && !canonical.equals(configured)) {
            throw new IllegalStateException(
                    MODE_PROPERTY + "=" + configured + " must be written as " + canonical
                            + "; the mode's beans are selected by that exact value, so any other"
                            + " spelling would report mode=" + canonical + " while running no mode"
                            + " at all"
            );
        }
    }
}
