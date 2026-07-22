package my.oj.web;

import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxCreatedListener;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxProcessor;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxProcessLock;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.mockito.Mockito.mock;

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
    void webRoleDisablesImmediateAndSchedulers() {
        contextRunner
                .withPropertyValues(
                        "contest.outbox.immediate.enabled=false",
                        "contest.outbox.scheduler.enabled=false",
                        "rank.streak.batch.enabled=false",
                        "contest.submission.judge.event-listener.enabled=false",
                        "contest.submission.judge.scheduler.enabled=false"
                )
                .run(context -> {
                    assertThatMissing(context, ContestScoreboardOutboxCreatedListener.class);
                    assertThatMissing(context, ContestScoreboardOutboxScheduler.class);
                    assertThatMissing(context, ContestSubmissionSubmittedListener.class);
                    assertThatMissing(context, ContestSubmissionResultListener.class);
                    assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
                    assertThatMissing(context, StreakRankBatchScheduler.class);
                });
    }

    @Test
    void batchRoleEnablesSchedulersButKeepsImmediateOff() {
        contextRunner
                .withPropertyValues(
                        "contest.outbox.immediate.enabled=false",
                        "contest.outbox.scheduler.enabled=true",
                        "rank.streak.batch.enabled=true",
                        "contest.submission.judge.event-listener.enabled=false",
                        "contest.submission.judge.scheduler.enabled=false"
                )
                .run(context -> {
                    assertThatMissing(context, ContestScoreboardOutboxCreatedListener.class);
                    assertThatMissing(context, ContestSubmissionSubmittedListener.class);
                    assertThatMissing(context, ContestSubmissionResultListener.class);
                    assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
                    assertThatPresent(context, ContestScoreboardOutboxScheduler.class);
                    assertThatPresent(context, StreakRankBatchScheduler.class);
                });
    }

    @Test
    void judgeRoleDisablesLegacyContestJudgeScheduler() {
        contextRunner
                .withPropertyValues(
                        "contest.outbox.immediate.enabled=false",
                        "contest.outbox.scheduler.enabled=false",
                        "rank.streak.batch.enabled=false",
                        "contest.submission.judge.event-listener.enabled=false",
                        "contest.submission.judge.scheduler.enabled=false"
                )
                .run(context -> {
                    assertThatMissing(context, ContestScoreboardOutboxCreatedListener.class);
                    assertThatMissing(context, ContestScoreboardOutboxScheduler.class);
                    assertThatMissing(context, ContestSubmissionSubmittedListener.class);
                    assertThatMissing(context, ContestSubmissionResultListener.class);
                    assertThatMissing(context, ContestSubmissionJudgeScheduler.class);
                    assertThatMissing(context, StreakRankBatchScheduler.class);
                });
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

    private static void assertThatMissing(org.springframework.context.ApplicationContext context, Class<?> type) {
        org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(type)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
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
}
