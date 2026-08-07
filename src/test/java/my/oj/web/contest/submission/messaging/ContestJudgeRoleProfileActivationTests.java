package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultBatchWriter;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultWriterProperties;
import my.oj.web.contest.submission.judge.JdbcContestSubmissionJudgeResultBatchPersistence;
import my.oj.web.observability.ContestOutboxDrainMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContestJudgeRoleProfileActivationTests {

    @Test
    void multiWebProfileStartsResultWriterWithoutJudgeMessagingWorkers() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-web")) {
            assertThatMissing(context, ContestJudgeOutboxRelay.class);
            assertThatMissing(context, ContestJudgeRabbitListener.class);
            assertThatPresent(context, ContestSubmissionJudgeResultBatchWriter.class);
        }
    }

    @Test
    void multiBatchProfileStartsJudgeOutboxRelayAndResultWriter() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-batch")) {
            assertThatPresent(context, ContestJudgeOutboxRelay.class);
            assertThatMissing(context, ContestJudgeRabbitListener.class);
            assertThatPresent(context, ContestSubmissionJudgeResultBatchWriter.class);
        }
    }

    @Test
    void multiJudgeProfileStartsListenerAndResultWriter() {
        try (ConfigurableApplicationContext context = runWithProfile("multi-judge")) {
            assertThatMissing(context, ContestJudgeOutboxRelay.class);
            assertThatPresent(context, ContestJudgeRabbitListener.class);
            assertThatPresent(context, ContestSubmissionJudgeResultBatchWriter.class);
        }
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

    private static void assertThatPresent(ConfigurableApplicationContext context, Class<?> type) {
        assertThat(context.getBeansOfType(type)).isNotEmpty();
    }

    private static void assertThatMissing(ConfigurableApplicationContext context, Class<?> type) {
        assertThat(context.getBeansOfType(type)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ContestJudgeOutboxRelayProperties.class,
            ContestSubmissionJudgeResultWriterProperties.class
    })
    @Import({
            ContestJudgeOutboxRelay.class,
            ContestJudgeRabbitListener.class,
            ContestSubmissionJudgeResultBatchWriter.class
    })
    static class ProfileTestConfiguration {

        @Bean
        ContestJudgeOutboxStore contestJudgeOutboxStore() {
            return mock(ContestJudgeOutboxStore.class);
        }

        /** Real rather than mocked: unbound to any registry it discards its recordings anyway. */
        @Bean
        ContestOutboxDrainMetrics contestOutboxDrainMetrics() {
            return new ContestOutboxDrainMetrics();
        }

        @Bean("contestJudgeRabbitTemplate")
        RabbitTemplate contestJudgeRabbitTemplate() {
            return mock(RabbitTemplate.class);
        }

        @Bean
        ContestSubmissionJudgeProcessor contestSubmissionJudgeProcessor() {
            return mock(ContestSubmissionJudgeProcessor.class);
        }

        @Bean
        JdbcContestSubmissionJudgeResultBatchPersistence judgeResultBatchPersistence() {
            return mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        }
    }
}
