package my.oj.web;

import my.oj.web.contest.scoreboard.redis.ContestRedisKeyValueClient;
import my.oj.web.contest.scoreboard.redis.RedisContestScoreboardWrongAttemptMetrics;
import my.oj.web.observability.ContestOutboxBacklogMetrics;
import my.oj.web.observability.ContestOutboxDrainMetrics;
import my.oj.web.observability.ContestOutboxMetricsProperties;
import my.oj.web.user.rank.streak.StreakRankBatchScheduler;
import my.oj.web.user.rank.streak.StreakRankBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContestOutboxBacklogMetrics is asserted here alongside the schedulers because the same
 * question decides all of them: which role runs this. It matters more for the backlog poller
 * than for the rest. An outbox backlog belongs to the table, not to the process reading it, so
 * a second instance publishing it does not add a second view - it makes sum(), the correct
 * operation for every other application metric in this repository, report twice the real
 * backlog. The failure is a wrong number on a dashboard rather than an error anywhere.
 */
class RoleBasedSchedulerActivationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TestDependencies.class,
                    ContestOutboxBacklogMetrics.class,
                    RedisContestScoreboardWrongAttemptMetrics.class,
                    StreakRankBatchScheduler.class
            );

    @Test
    void multiWebProfileDisablesSchedulers() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-web")) {
            assertThatProfileIsActive(context, "multi-server");
            assertThatProfileIsActive(context, "web-role");
            assertThatMissing(context, StreakRankBatchScheduler.class);
            assertThatMissing(context, ContestOutboxBacklogMetrics.class);
            assertThatMissing(context, RedisContestScoreboardWrongAttemptMetrics.class);
        }
    }

    @Test
    void multiBatchProfileEnablesSchedulers() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-batch")) {
            assertThatProfileIsActive(context, "multi-server");
            assertThatProfileIsActive(context, "batch-role");
            assertThatPresent(context, StreakRankBatchScheduler.class);
            assertThat(context.getEnvironment().getProperty(
                    "contest.scoreboard.stream.consumer.enabled", Boolean.class)).isTrue();
            assertThatPresent(context, ContestOutboxBacklogMetrics.class);
            assertThatPresent(context, RedisContestScoreboardWrongAttemptMetrics.class);
        }
    }

    @Test
    void multiJudgeProfileDisablesSchedulers() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-judge")) {
            assertThatProfileIsActive(context, "multi-server");
            assertThatProfileIsActive(context, "judge-role");
            assertThatMissing(context, StreakRankBatchScheduler.class);
            assertThatMissing(context, ContestOutboxBacklogMetrics.class);
            assertThatMissing(context, RedisContestScoreboardWrongAttemptMetrics.class);
        }
    }

    @Test
    void defaultsEnableSchedulers() {
        contextRunner
                .run(context -> {
                    assertThatPresent(context, StreakRankBatchScheduler.class);
                    assertThatPresent(context, ContestOutboxBacklogMetrics.class);
                    assertThatMissing(context, RedisContestScoreboardWrongAttemptMetrics.class);
                });
    }

    private static void assertThatPresent(org.springframework.context.ApplicationContext context, Class<?> type) {
        org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(type)).isNotEmpty();
    }

    private static ConfigurableApplicationContext runWithProfile(String profile) {
        SpringApplication application = new SpringApplication(ProfileTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(
                "--spring.profiles.active=" + profile,
                "--spring.config.location=file:./src/main/resources/",
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false"
        );
    }

    private static void assertThatMissing(org.springframework.context.ApplicationContext context, Class<?> type) {
        org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(type)).isEmpty();
    }

    private static void assertThatProfileIsActive(org.springframework.context.ApplicationContext context,
                                                  String profile) {
        org.assertj.core.api.Assertions.assertThat(context.getEnvironment().acceptsProfiles(Profiles.of(profile)))
                .as("profile %s should be active; active profiles: %s; multi-web group: %s",
                        profile,
                        java.util.Arrays.toString(context.getEnvironment().getActiveProfiles()),
                        context.getEnvironment().getProperty("spring.profiles.group.multi-web[0]"))
                .isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ContestOutboxMetricsProperties.class
    })
    static class TestDependencies {

        @Bean
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate() {
            return mock(org.springframework.jdbc.core.JdbcTemplate.class);
        }

        /** Real rather than mocked: unbound to any registry it discards its recordings anyway. */
        @Bean
        ContestOutboxDrainMetrics contestOutboxDrainMetrics() {
            return new ContestOutboxDrainMetrics();
        }

        @Bean
        StreakRankBatchService streakRankBatchService() {
            return mock(StreakRankBatchService.class);
        }

        @Bean
        ContestRedisKeyValueClient contestRedisKeyValueClient() {
            return mock(ContestRedisKeyValueClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            TestDependencies.class,
            ContestOutboxBacklogMetrics.class,
            RedisContestScoreboardWrongAttemptMetrics.class,
            StreakRankBatchScheduler.class
    })
    static class ProfileTestConfiguration {
    }
}
