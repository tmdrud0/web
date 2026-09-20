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
 *
 * <h2>Why the owner declaration is checked here</h2>
 *
 * <p>Every gate and lock in this package is JVM-local. Two instances that both run a recovery pass
 * will both run it, and nothing in this application prevents that - the deployment's single
 * {@code batch-role} instance is the only thing that does. That makes the assumption worth stating
 * and worth checking, because the failure it guards is silent: a second batch instance starts, looks
 * healthy, and its replay races the first one's allocator read. The checks below stop the two ways a
 * deployment ends up with the assumption broken rather than merely unstated.</p>
 */
@Component
public class ContestScoreboardRecoveryValidator implements SmartInitializingSingleton {

    static final String MODE_PROPERTY = "contest.scoreboard.recovery.mode";
    static final String OWNER_PROPERTY = "contest.scoreboard.recovery.owner.enabled";
    static final String STREAM_CONSUMER_PROPERTY = "contest.scoreboard.stream.consumer.enabled";

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
        rejectOwnerMismatch();
        String store = ContestScoreboardStoreProperty.value(environment);
        if (properties.mode() == ContestScoreboardRecoveryMode.REDIS_SEQ && !"redis".equals(store)) {
            throw new IllegalStateException(
                    "contest.scoreboard.recovery.mode=redis-seq requires contest.scoreboard.store=redis"
                            + " but the configured store is '" + store + "'"
            );
        }
    }

    /**
     * Refuses a role whose owner declaration contradicts what it actually runs.
     *
     * <p>Both directions are refusals rather than warnings because both are quiet by nature. A
     * consumer that declares itself no owner runs the supervisor pass all the same, so the
     * declaration would be a false statement about the one instance that does recover; and an owner
     * whose only trigger is gone claims the role while running nothing, which reads in the startup
     * log exactly like an instance that is recovering.</p>
     */
    private void rejectOwnerMismatch() {
        boolean consumer = environment.getProperty(STREAM_CONSUMER_PROPERTY, Boolean.class, false);
        if (consumer && !properties.owner().enabled()) {
            throw new IllegalStateException(
                    STREAM_CONSUMER_PROPERTY + "=true while " + OWNER_PROPERTY + "=false: this instance"
                            + " consumes the scoreboard stream and runs the supervisor pass that repairs a"
                            + " rollback, so it is a recovery owner whether it declares itself one or not."
                            + " Declaring otherwise would leave the instance that actually recovers outside"
                            + " the declaration, which is how a second batch instance gets started without"
                            + " anyone noticing."
            );
        }
        if (properties.owner().enabled()
                && !consumer
                && properties.mode() == ContestScoreboardRecoveryMode.STREAM_OFFSET) {
            throw new IllegalStateException(
                    OWNER_PROPERTY + "=true while " + STREAM_CONSUMER_PROPERTY + "=false and "
                            + MODE_PROPERTY + "=stream-offset: this mode's only trigger is the stream"
                            + " consumer's supervisor pass, so an owner declared here would own nothing"
                            + " while claiming the recovery role. Turn the owner declaration off on this"
                            + " role, or turn the consumer on."
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
