package my.oj.web.contest.scoreboard.recovery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import my.oj.web.contest.scoreboard.ContestScoreboardAppliedMarker;
import my.oj.web.contest.scoreboard.ContestScoreboardApplier;
import my.oj.web.contest.scoreboard.ContestScoreboardApplyLock;
import my.oj.web.contest.scoreboard.ContestScoreboardSequenceSource;
import my.oj.web.contest.submission.core.ContestSubmissionResultRepository;
import my.oj.web.contest.submission.support.ContestSubmissionBatchExecutor;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardMySqlPollConfiguration;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardMySqlPollLifecycle;
import my.oj.web.contest.scoreboard.poll.ContestScoreboardMySqlPoller;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The shipped role files, read from disk, as the proof that the owner declaration in them is what
 * keeps a role out of recovery.
 *
 * <p>{@link ContestScoreboardRecoveryModeWiringTests} answers the same question from property values
 * the test supplies. This class answers it from the files a deployment actually reads, because the
 * claim is about those files: {@code multi-web} and {@code multi-judge} declare themselves no owner,
 * and editing one of them must not be able to start a recovery pass quietly. Loading them through a
 * real {@code SpringApplication} is what makes the group profiles and the file precedence part of the
 * evidence rather than something the test restates.</p>
 *
 * <p>The positive control is the third role. The same file set, the same context and the same beans,
 * with {@code multi-batch} - and there the mode's trigger does come up. Without it, "no trigger on
 * this role" would pass for a context that brought up nothing at all.</p>
 *
 * <p>A role is also read pointed at a recovery mode it does not ship. A web role whose mode was
 * changed for its reporting - the mode is a property like any other - must still register nothing:
 * the mode axis alone would bring the trigger up, and only the owner declaration keeps it down.</p>
 */
class ContestScoreboardRecoveryRoleGateTests {

    @Test
    void theWebRoleRegistersNoRecoveryPass() {
        assertNoRecoveryPass("multi-web");
    }

    @Test
    void theJudgeRoleRegistersNoRecoveryPass() {
        assertNoRecoveryPass("multi-judge");
    }

    @Test
    void aRoleThatDoesNotOwnRecoveryRegistersNothingEvenInARecoveryMode() {
        assertNoRecoveryPass("multi-web,redis-seq-poll");
        assertNoRecoveryPass("multi-judge,redis-seq-poll");
        assertNoRecoveryPass("multi-judge", "--contest.scoreboard.recovery.mode=full-replay");
    }

    @Test
    void theBatchRoleRegistersTheTriggerItsModeSelects() {
        try (ConfigurableApplicationContext context = run("multi-batch", "--contest.scoreboard.recovery.mode=full-replay")) {
            assertThat(context.getBeansOfType(ContestScoreboardFullReplayStartupRunner.class))
                    .as("multi-batch with mode=full-replay")
                    .hasSize(1);
        }
        try (ConfigurableApplicationContext context = run("multi-batch,redis-seq-poll")) {
            assertThat(context.getBeansOfType(ContestScoreboardMySqlPoller.class))
                    .as("multi-batch with redis-seq-poll")
                    .hasSize(1);
            assertThat(context.getBeansOfType(ContestScoreboardMySqlPollLifecycle.class))
                    .as("multi-batch with redis-seq-poll")
                    .hasSize(1);
        }
    }

    private void assertNoRecoveryPass(String profile, String... extraProperties) {
        try (ConfigurableApplicationContext context = run(profile, extraProperties)) {
            assertThat(context.getBeansOfType(ContestScoreboardFullReplayStartupRunner.class))
                    .as("%s should not replay at startup", profile)
                    .isEmpty();
            assertThat(context.getBeansOfType(ContestScoreboardMySqlPoller.class))
                    .as("%s should register no MySQL poller", profile)
                    .isEmpty();
            assertThat(context.getBeansOfType(ContestScoreboardMySqlPollLifecycle.class))
                    .as("%s should run no poll, rollback check or range recovery", profile)
                    .isEmpty();
        }
    }

    private ConfigurableApplicationContext run(String profile, String... extraProperties) {
        SpringApplication application = new SpringApplication(ProfileTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        String[] arguments = new String[4 + extraProperties.length];
        arguments[0] = "--spring.profiles.active=" + profile;
        arguments[1] = "--spring.config.location=file:./src/main/resources/";
        arguments[2] = "--spring.main.banner-mode=off";
        arguments[3] = "--spring.jmx.enabled=false";
        System.arraycopy(extraProperties, 0, arguments, 4, extraProperties.length);
        return application.run(arguments);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContestScoreboardRecoveryProperties.class)
    static class TestDependencies {

        @Bean
        ContestScoreboardApplier scoreboardApplier() {
            return mock(ContestScoreboardApplier.class);
        }

        @Bean
        ContestScoreboardSequenceSource sequenceSource() {
            return mock(ContestScoreboardSequenceSource.class);
        }

        @Bean
        ContestScoreboardApplyLock applyLock() {
            return mock(ContestScoreboardApplyLock.class);
        }

        @Bean
        ContestScoreboardAppliedMarker appliedMarker() {
            return mock(ContestScoreboardAppliedMarker.class);
        }

        @Bean
        ContestSubmissionResultRepository resultRepository() {
            return mock(ContestSubmissionResultRepository.class);
        }

        @Bean
        ContestSubmissionBatchExecutor batchExecutor() {
            return mock(ContestSubmissionBatchExecutor.class);
        }

        @Bean
        StringRedisTemplate redisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ContestScoreboardRecoveryPassGate recoveryPassGate(MeterRegistry meterRegistry) {
            return new ContestScoreboardRecoveryPassGate(meterRegistry);
        }
    }

    /**
     * The recovery beans a role could register, and the validator that judges the declaration - so a
     * role file that both registers a trigger and contradicts itself cannot pass by failing to start.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({
            TestDependencies.class,
            ContestScoreboardRecoveryValidator.class,
            ContestScoreboardRecoveryCutover.class,
            ContestScoreboardFullReplayService.class,
            ContestScoreboardReplayApplication.class,
            ContestScoreboardFullReplayStartupRunner.class,
            ContestScoreboardRedisSequenceConfig.class,
            ContestScoreboardMySqlPollConfiguration.class
    })
    static class ProfileTestConfiguration {
    }
}
