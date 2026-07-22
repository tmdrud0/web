package my.oj.web;

import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxCreatedListener;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxProcessor;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxProcessLock;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxProperties;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRecoveryService;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxRepository;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxScheduler;
import my.oj.web.contest.submission.core.ContestSubmissionRepository;
import my.oj.web.contest.submission.core.ContestSubmissionService;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeScheduler;
import my.oj.web.submission.event.contest.ContestSubmissionResultListener;
import my.oj.web.submission.event.contest.ContestSubmissionSubmittedListener;
import my.oj.web.submission.judge.Judgement;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

class RoleBasedSchedulerActivationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TestDependencies.class,
                    ContestScoreboardOutboxCreatedListener.class,
                    ContestScoreboardOutboxScheduler.class,
                    ContestSubmissionSubmittedListener.class,
                    ContestSubmissionResultListener.class,
                    ContestSubmissionJudgeScheduler.class,
                    StreakRankBatchScheduler.class
            );

    @Test
    void multiWebProfileDisablesImmediateAndSchedulers() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-web")) {
            assertThatProfileIsActive(context, "multi-server");
            assertThatProfileIsActive(context, "web-role");
            assertThatMissing(context, ContestScoreboardOutboxCreatedListener.class);
            assertThatMissing(context, ContestScoreboardOutboxScheduler.class);
            assertThatMissing(context, ContestSubmissionSubmittedListener.class);
            assertThatMissing(context, ContestSubmissionResultListener.class);
            assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
            assertThatMissing(context, StreakRankBatchScheduler.class);
        }
    }

    @Test
    void multiBatchProfileEnablesSchedulersButKeepsImmediateOff() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-batch")) {
            assertThatProfileIsActive(context, "multi-server");
            assertThatProfileIsActive(context, "batch-role");
            assertThatMissing(context, ContestScoreboardOutboxCreatedListener.class);
            assertThatMissing(context, ContestSubmissionSubmittedListener.class);
            assertThatMissing(context, ContestSubmissionResultListener.class);
            assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
            assertThatPresent(context, ContestScoreboardOutboxScheduler.class);
            assertThatPresent(context, StreakRankBatchScheduler.class);
            ContestScoreboardOutboxProperties properties = context.getBean(ContestScoreboardOutboxProperties.class);
            assertThat(properties.batchSize()).isEqualTo(500);
            assertThat(properties.recoveryBatchSize()).isEqualTo(10);
            assertThat(properties.claimTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
        }
    }

    @Test
    void multiJudgeProfileDisablesLegacyContestJudgeScheduler() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-judge")) {
            assertThatProfileIsActive(context, "multi-server");
            assertThatProfileIsActive(context, "judge-role");
            assertThatMissing(context, ContestScoreboardOutboxCreatedListener.class);
            assertThatMissing(context, ContestScoreboardOutboxScheduler.class);
            assertThatMissing(context, ContestSubmissionSubmittedListener.class);
            assertThatMissing(context, ContestSubmissionResultListener.class);
            assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
            assertThatMissing(context, StreakRankBatchScheduler.class);
        }
    }

    @Test
    void defaultsEnableImmediateAndEventListeners() {
        contextRunner
                .run(context -> {
                    assertThatPresent(context, ContestScoreboardOutboxCreatedListener.class);
                    assertThatPresent(context, ContestScoreboardOutboxScheduler.class);
                    assertThatPresent(context, ContestSubmissionSubmittedListener.class);
                    assertThatPresent(context, ContestSubmissionResultListener.class);
                    assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
                    assertThatPresent(context, StreakRankBatchScheduler.class);
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
    @EnableConfigurationProperties(ContestScoreboardOutboxProperties.class)
    static class TestDependencies {

        @Bean
        ContestScoreboardOutboxProcessor contestScoreboardOutboxProcessor() {
            return mock(ContestScoreboardOutboxProcessor.class);
        }

        @Bean
        ContestScoreboardOutboxRecoveryService contestScoreboardOutboxRecoveryService() {
            return mock(ContestScoreboardOutboxRecoveryService.class);
        }

        @Bean
        ContestScoreboardOutboxProcessLock contestScoreboardOutboxProcessLock() {
            return mock(ContestScoreboardOutboxProcessLock.class);
        }

        @Bean
        ContestScoreboardOutboxRepository contestScoreboardOutboxRepository() {
            return mock(ContestScoreboardOutboxRepository.class);
        }

        @Bean
        ContestSubmissionService contestSubmissionService() {
            return mock(ContestSubmissionService.class);
        }

        @Bean
        ContestSubmissionRepository contestSubmissionRepository() {
            return mock(ContestSubmissionRepository.class);
        }

        @Bean
        ContestSubmissionJudgeProcessor contestSubmissionJudgeProcessor() {
            return mock(ContestSubmissionJudgeProcessor.class);
        }

        @Bean(name = "contestSubmissionExecutor")
        ThreadPoolTaskExecutor contestSubmissionExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(10);
            executor.initialize();
            return executor;
        }

        @Bean(name = "contestJudgement")
        Judgement contestJudgement() {
            return mock(Judgement.class);
        }

        @Bean
        StreakRankBatchService streakRankBatchService() {
            return mock(StreakRankBatchService.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            TestDependencies.class,
            ContestScoreboardOutboxCreatedListener.class,
            ContestScoreboardOutboxScheduler.class,
            ContestSubmissionSubmittedListener.class,
            ContestSubmissionResultListener.class,
            ContestSubmissionJudgeScheduler.class,
            StreakRankBatchScheduler.class
    })
    static class ProfileTestConfiguration {
    }
}
