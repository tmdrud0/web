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
 * <p>{@code contest.scoreboard.recovery.owner.enabled} removes this instance's recovery triggers:
 * {@link ContestScoreboardRecoveryOwnerCondition} keeps the full-replay startup runner, the sequence
 * startup check and the sequence scheduler out of the context when it is false. What no condition can
 * remove is the supervisor pass, which is a method on the stream consumer's lifecycle rather than a
 * trigger of its own - an instance with the consumer on runs it whatever the owner says.</p>
 *
 * <p>That is the mismatch the first check below refuses: it is not that the declaration was ignored,
 * but that a consumer makes it untrue. The second check refuses the mirror image - an owner with no
 * consumer in the mode whose only trigger is that consumer's supervisor, which now also has no mode
 * trigger to fall back on, so the role would report itself as recovering while running nothing.</p>
 *
 * <p>Neither check says anything about two instances. Every gate and lock in this package is
 * JVM-local, so two owners both recover, and the deployment's single {@code batch-role} instance is
 * the only thing that prevents it - see {@code ARCHITECTURE.md} §3.5.</p>
 *
 * <h2>Why the cold-start pair is checked here</h2>
 *
 * <p>In {@code full-replay} and {@code redis-seq} the consumer is held until the mode's own basis has
 * rebuilt the history a restored Redis is missing, because a consumer that started first re-reads that
 * history from the stream - the basis of {@code stream-offset} - and the mode's basis then runs over a
 * scoreboard that no longer needs it. What the hold itself is, and why it cannot lose a result, is
 * {@link ContestScoreboardRecoveryCutover}.</p>
 *
 * <p>The third check below refuses a consumer whose mode's <em>only</em> release of that hold is
 * turned off. That is {@code full-replay} alone: its startup runner is the one thing that reports the
 * boundary, and the retention-gap fallback - the only other caller of the replay - needs a running
 * consumer to be reached at all. {@code redis-seq} is deliberately not refused when
 * {@code startup-check-enabled=false}, because that property removes the first check rather than the
 * mechanism: the scheduler registers both periodic checks whether or not it is set, the hold is what
 * keeps the consumer waiting, and the first period releases it one interval later. The mode loses the
 * head start the startup check exists to give it - which is what
 * {@link ContestScoreboardRedisSequenceStartupCheck}'s own log says - and nothing else.</p>
 *
 * <h2>Why the consumer's flag is read as the condition reads it</h2>
 *
 * <p>The consumer beans are selected by {@code @ConditionalOnProperty}, which compares the written
 * value with {@code true} ignoring case and nothing else, while a {@code Boolean.class} binding would
 * also accept {@code yes}, {@code on} and {@code 1}. Reading it the lenient way would let this class
 * refuse a configuration that registers no consumer at all - a false refusal caused only by spelling -
 * so the flag is read the way the beans that answer to it are selected.</p>
 */
@Component
public class ContestScoreboardRecoveryValidator implements SmartInitializingSingleton {

    static final String MODE_PROPERTY = "contest.scoreboard.recovery.mode";
    static final String OWNER_PROPERTY = "contest.scoreboard.recovery.owner.enabled";
    static final String STREAM_CONSUMER_PROPERTY = "contest.scoreboard.stream.consumer.enabled";
    static final String STARTUP_REPLAY_PROPERTY =
            "contest.scoreboard.recovery.full-replay.startup-replay-enabled";

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
        rejectAConsumerWithNoStartupRecovery();
        String store = ContestScoreboardStoreProperty.value(environment);
        if (properties.mode() == ContestScoreboardRecoveryMode.REDIS_SEQ && !"redis".equals(store)) {
            throw new IllegalStateException(
                    "contest.scoreboard.recovery.mode=redis-seq requires contest.scoreboard.store=redis"
                            + " but the configured store is '" + store + "'"
            );
        }
    }

    /**
     * Refuses a mode that consumes the stream while the only thing that releases its hold is off.
     *
     * <p>The two settings are not independent in {@code full-replay}: the startup runner is the one
     * thing that reports the history-recovery boundary, so turning the replay off with the consumer on
     * leaves the consumer waiting for a boundary nothing can report. Nothing publishes the boundary
     * later either - the retention-gap fallback reaches the replay through a stream delivery, which
     * needs the consumer that is waiting, and the operator rebuild endpoint rebuilds through a
     * different path and never reports it. The mode's own basis would not run, and the instance would
     * consume nothing at all rather than recover by the mechanism the operator selected.</p>
     *
     * <p>{@code redis-seq} is not refused here. Its hold is released by any completed periodic check,
     * not only by the startup one, so {@code startup-check-enabled=false} costs the head start and
     * nothing more - see this class's javadoc. Refusing it would make the property unusable on every
     * role that consumes while asserting a substitution that the scheduler's own first period
     * prevents.</p>
     *
     * <p>Either half resolves the refused one. An instance that should only repair a rollback at
     * runtime rather than rebuild at boot belongs on a role with the consumer off; an instance that
     * consumes the stream keeps the mode's startup replay on and is held behind it.</p>
     */
    private void rejectAConsumerWithNoStartupRecovery() {
        if (!consumerEnabled()) {
            return;
        }
        if (properties.mode() == ContestScoreboardRecoveryMode.FULL_REPLAY
                && !properties.fullReplay().startupReplayEnabled()) {
            throw new IllegalStateException(STARTUP_REPLAY_PROPERTY + "=false while "
                    + STREAM_CONSUMER_PROPERTY + "=true and " + MODE_PROPERTY + "=full-replay: this mode's"
                    + " history recovery is its startup replay, and the consumer is held until that replay"
                    + " has run. Nothing else reports that boundary - the retention-gap fallback needs the"
                    + " consumer that is waiting, and the operator rebuild endpoint never reports it - so the"
                    + " instance would consume nothing rather than recover by the basis this mode is named"
                    + " for. Keep the startup replay on, or turn " + STREAM_CONSUMER_PROPERTY + " off on"
                    + " this role."
            );
        }
    }

    /**
     * Whether the consumer beans are registered, read as {@code @ConditionalOnProperty} reads it.
     *
     * <p>Not through a {@code Boolean.class} binding: a lenient conversion accepts spellings that the
     * condition turns down, so this class would refuse a configuration that has no consumer in it.</p>
     */
    private boolean consumerEnabled() {
        String configured = environment.getProperty(STREAM_CONSUMER_PROPERTY);
        return configured != null && "true".equalsIgnoreCase(configured);
    }

    /**
     * Refuses a role whose owner declaration contradicts what it actually runs.
     *
     * <p>Both directions are refusals rather than warnings because both are quiet by nature. A
     * consumer that declares itself no owner runs the supervisor pass all the same - the owner
     * condition cannot remove it - so the declaration would be a false statement about the one
     * instance that does recover; and an owner in {@code stream-offset} with the consumer off has
     * neither the supervisor pass nor any mode trigger to run, which reads in the startup log exactly
     * like an instance that is recovering.</p>
     */
    private void rejectOwnerMismatch() {
        boolean consumer = consumerEnabled();
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
