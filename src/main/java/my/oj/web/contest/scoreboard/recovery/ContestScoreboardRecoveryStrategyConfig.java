package my.oj.web.contest.scoreboard.recovery;

import my.oj.web.contest.scoreboard.stream.ContestScoreboardStreamRecoveryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gives the live scoreboard consumer the one strategy its mode selects.
 *
 * <h2>Why a factory and not {@code @ConditionalOnProperty} on each strategy</h2>
 *
 * <p>The mode's trigger beans are gated on the property string, because they are independent
 * components that happen to share a switch. This is not one of those: the consumer needs exactly one
 * strategy, and which one is a total function of the mode. An exhaustive switch says so, and a mode
 * added to the enum without an answer here stops the build rather than starting a JVM whose consumer
 * has no strategy at all.</p>
 *
 * <p>It also removes an ordering trap the property-string gate has. {@code @ConditionalOnProperty}
 * compares the value as written while the record binds it leniently, so {@code FULL_REPLAY} would
 * select no strategy while the startup report called the mode full-replay. The validator rejects that
 * spelling, and this factory is the second reason it cannot slip through - it reads the bound enum,
 * which is the same value the report prints.</p>
 *
 * <p>Conditional on the consumer for the same reason the consumer's own beans are: this is the
 * consumer's view of recovery, and a role that does not consume the stream has no use for it.
 * Reaching a mode-specific service is deliberately lazy - the switch guarantees the service for the
 * bound mode exists, so a failure to find it means the mode and its beans have come apart, which
 * should stop the JVM.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
public class ContestScoreboardRecoveryStrategyConfig {

    @Bean
    ContestScoreboardRecoveryStrategy contestScoreboardRecoveryStrategy(
            ContestScoreboardRecoveryProperties properties,
            ContestScoreboardRecoveryPassGate gate,
            ObjectProvider<ContestScoreboardStreamRecoveryService> streamRecovery,
            ContestScoreboardFullReplayService fullReplay,
            ObjectProvider<ContestScoreboardRedisSequenceRecoveryService> sequenceRecovery,
            ContestScoreboardTouchedContests touchedContests
    ) {
        return switch (properties.mode()) {
            case STREAM_OFFSET -> new StreamOffsetRecoveryStrategy(streamRecovery.getObject(), gate);
            // Only an explicit `synchronous` keeps the old behaviour; a value that bound to nothing is the default.
            case FULL_REPLAY -> properties.fullReplay().rollbackReplay() == ContestScoreboardRecoveryProperties.RollbackReplay.SYNCHRONOUS
                    ? new FullReplayRecoveryStrategy(fullReplay, gate)
                    : new FullReplayRecoveryStrategy(fullReplay, gate, touchedContests,
                            new ContestScoreboardBackgroundReplay(fullReplay, gate,
                                    properties.fullReplay().backgroundRetryBackoff()));
            case REDIS_SEQ -> new RedisSequenceRecoveryStrategy(sequenceRecovery.getObject(), gate);
        };
    }
}
