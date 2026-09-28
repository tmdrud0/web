package my.oj.web.contest.scoreboard.recovery;

import lombok.extern.slf4j.Slf4j;
import my.oj.web.contest.scoreboard.delivery.ContestScoreboardDelivery;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardMySqlPollProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reports the recovery mode and its effective settings once at startup.
 *
 * <p>Without this the mode is only discoverable by reading the deployment's property files, which
 * is exactly the gap that made the previous, config-only recovery settings look implemented.</p>
 */
@Component
@Slf4j
public class ContestScoreboardRecoveryReporter implements ApplicationRunner {

    private final ContestScoreboardRecoveryProperties properties;
    private final Environment environment;

    public ContestScoreboardRecoveryReporter(ContestScoreboardRecoveryProperties properties,
                                             Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Contest scoreboard recovery: {} applied-at-tracking={}", ContestScoreboardRecoverySummary.describe(
                properties.mode(),
                ContestScoreboardStoreProperty.value(environment),
                ContestScoreboardDelivery.of(environment),
                properties,
                pollProperties(environment)
        ), my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking.resolve(
                my.oj.web.contest.scoreboard.ContestScoreboardAppliedAtTracking.configured(environment),
                properties.mode()));
    }

    /**
     * Bound here rather than injected: the poll properties are registered with the poller, which exists on
     * the recovery owner only, while every role prints this report.
     */
    static ContestScoreboardMySqlPollProperties pollProperties(Environment environment) {
        return Binder.get(environment).bindOrCreate("contest.scoreboard.mysql-poll",
                ContestScoreboardMySqlPollProperties.class);
    }
}
