package my.oj.web.contest.scoreboard.poll;

import io.micrometer.core.instrument.MeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryOwnerCondition;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardSequencedApplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * The {@code mysql-poll} delivery, on the recovery owner only.
 *
 * <p>Both conditions are bean conditions, so an instance that is not the owner has no poller at all -
 * not one that declines. The owner is the single {@code batch-role} instance; the MySQL named lock in
 * {@link MySqlNamedLockPollOwnership} makes a mistakenly started second owner stand down rather than
 * poll alongside it.</p>
 */
@Configuration(proxyBeanMethods = false)
@Conditional({MySqlPollDeliveryCondition.class, ContestScoreboardRecoveryOwnerCondition.class})
@EnableConfigurationProperties(ContestScoreboardMySqlPollProperties.class)
public class ContestScoreboardMySqlPollConfiguration {

    @Bean
    ContestScoreboardMySqlPollMetrics contestScoreboardMySqlPollMetrics(MeterRegistry meterRegistry) {
        return new ContestScoreboardMySqlPollMetrics(meterRegistry);
    }

    @Bean
    ContestScoreboardSequenceLedger contestScoreboardSequenceLedger(JdbcTemplate jdbcTemplate,
                                                                   PlatformTransactionManager transactionManager) {
        return new JdbcContestScoreboardSequenceLedger(jdbcTemplate, transactionManager);
    }

    @Bean
    ContestScoreboardSequencedApplier contestScoreboardSequencedApplier(StringRedisTemplate redisTemplate) {
        return new RedisContestScoreboardSequencedApplier(redisTemplate);
    }

    @Bean
    ContestScoreboardSequencedApplication contestScoreboardSequencedApplication(
            ContestScoreboardSequencedApplier applier, ContestScoreboardSequenceLedger ledger) {
        return new ContestScoreboardSequencedApplication(applier, ledger);
    }

    @Bean
    ContestScoreboardRollbackDetector contestScoreboardRollbackDetector(ContestScoreboardSequencedApplier applier,
                                                                       ContestScoreboardSequenceLedger ledger,
                                                                       ContestScoreboardApplyLock applyLock,
                                                                       ContestScoreboardMySqlPollMetrics metrics) {
        return new ContestScoreboardRollbackDetector(applier, ledger, applyLock, metrics);
    }

    @Bean
    ContestScoreboardMySqlPoller contestScoreboardMySqlPoller(ContestScoreboardSequenceLedger ledger,
                                                             ContestScoreboardSequencedApplication application,
                                                             ContestScoreboardRollbackDetector detector,
                                                             ContestScoreboardApplyLock applyLock,
                                                             ContestScoreboardMySqlPollMetrics metrics,
                                                             ContestScoreboardMySqlPollProperties properties) {
        return new ContestScoreboardMySqlPoller(ledger, application, detector, applyLock, metrics,
                properties.batchSize());
    }

    @Bean
    ContestScoreboardPollOwnership contestScoreboardPollOwnership(DataSource dataSource,
                                                                 ContestScoreboardMySqlPollProperties properties) {
        return new MySqlNamedLockPollOwnership(dataSource, properties.ownershipLockName());
    }

    @Bean
    ContestScoreboardMySqlPollLifecycle contestScoreboardMySqlPollLifecycle(
            ContestScoreboardMySqlPoller poller,
            ContestScoreboardRollbackDetector detector,
            ContestScoreboardPollOwnership ownership,
            ContestScoreboardMySqlPollMetrics metrics,
            ContestScoreboardMySqlPollProperties properties) {
        return new ContestScoreboardMySqlPollLifecycle(poller, detector, ownership, metrics,
                properties);
    }
}
