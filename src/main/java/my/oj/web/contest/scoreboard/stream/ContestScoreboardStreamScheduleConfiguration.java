package my.oj.web.contest.scoreboard.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The two periodic jobs of the stream consumer, on the intervals the settings record holds.
 *
 * <p>They are registered here rather than with {@code @Scheduled(fixedDelayString = ...)} so each
 * interval has exactly one definition. A placeholder would spell the default twice - once in the
 * annotation and once in the record - and the annotation is what the scheduler obeys, so the value an
 * operator reads back from configuration would be the one that does not take effect.</p>
 *
 * <p>Both keep the timing they had under {@code @Scheduled}: the first run happens as soon as the
 * context is up, and each later run follows the previous one by the interval.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
class ContestScoreboardStreamScheduleConfiguration implements SchedulingConfigurer {

    private final ContestScoreboardStreamConsumerProperties properties;
    private final ContestScoreboardStreamLifecycle lifecycle;
    private final ContestScoreboardStreamTailOffsetMonitor tailOffsetMonitor;

    ContestScoreboardStreamScheduleConfiguration(
            ContestScoreboardStreamConsumerProperties properties,
            ContestScoreboardStreamLifecycle lifecycle,
            ContestScoreboardStreamTailOffsetMonitor tailOffsetMonitor
    ) {
        this.properties = properties;
        this.lifecycle = lifecycle;
        this.tailOffsetMonitor = tailOffsetMonitor;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(lifecycle::recoverConsumption, properties.offsetCheckInterval());
        taskRegistrar.addFixedDelayTask(tailOffsetMonitor::observeTailOffset, properties.tailProbeInterval());
    }
}
