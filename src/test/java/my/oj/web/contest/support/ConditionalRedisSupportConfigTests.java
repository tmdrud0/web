package my.oj.web.contest.support;

import my.oj.web.contest.scoreboard.ContestScoreboardService;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxApplier;
import my.oj.web.contest.scoreboard.outbox.ContestScoreboardOutboxApplierConfig;
import my.oj.web.contest.scoreboard.outbox.DirectContestScoreboardOutboxApplier;
import my.oj.web.contest.scoreboard.outbox.RedisContestScoreboardOutboxApplier;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.ContestSubmissionDuplicateRegistryConfig;
import my.oj.web.contest.submission.support.NoopContestSubmissionDuplicateRegistry;
import my.oj.web.contest.submission.support.RedisContestSubmissionDuplicateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConditionalRedisSupportConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ContestScoreboardOutboxApplierConfig.class,
                    ContestSubmissionDuplicateRegistryConfig.class,
                    ScoreboardDependencies.class
            );

    @Test
    void fallsBackToNoopBeansWithoutRedisProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ContestScoreboardOutboxApplier.class);
            assertThat(context).hasSingleBean(ContestSubmissionDuplicateRegistry.class);
            assertThat(context.getBean(ContestScoreboardOutboxApplier.class))
                    .isInstanceOf(DirectContestScoreboardOutboxApplier.class);
            assertThat(context.getBean(ContestSubmissionDuplicateRegistry.class))
                    .isInstanceOf(NoopContestSubmissionDuplicateRegistry.class);
        });
    }

    @Test
    void wiresRedisImplementationsWhenRedisStoreIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "contest.scoreboard.store=redis",
                        "contest.submission.dedup.store=redis"
                )
                .withUserConfiguration(RedisDependencies.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ContestScoreboardOutboxApplier.class);
                    assertThat(context).hasSingleBean(ContestSubmissionDuplicateRegistry.class);
                    assertThat(context.getBean(ContestScoreboardOutboxApplier.class))
                            .isInstanceOf(RedisContestScoreboardOutboxApplier.class);
                    assertThat(context.getBean(ContestSubmissionDuplicateRegistry.class))
                            .isInstanceOf(RedisContestSubmissionDuplicateRegistry.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ScoreboardDependencies {

        @Bean
        ContestScoreboardService contestScoreboardService() {
            return mock(ContestScoreboardService.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisDependencies {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }
}
