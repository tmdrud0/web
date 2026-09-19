package my.oj.web.contest.submission.messaging;

import my.oj.web.contest.submission.judge.ContestSubmissionJudgeProcessor;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultBatchWriter;
import my.oj.web.contest.submission.judge.ContestSubmissionJudgeResultStreamPublisher;
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

    @Test
    void mysqlModeReplacesRabbitListenerOnJudgeRole() {
        try (ConfigurableApplicationContext context = runWithProfile(
                "multi-judge",
                "--contest.submission.judge.dispatch-mode=mysql",
                "--contest.submission.judge.mysql.poll-interval=1h")) {
            assertThatMissing(context, ContestJudgeOutboxRelay.class);
            assertThatMissing(context, ContestJudgeRabbitListener.class);
            assertThatPresent(context, MysqlContestJudgeDispatcher.class);
        }
    }

    @Test
    void mysqlModeDoesNotStartClaimantOnBatchRole() {
        try (ConfigurableApplicationContext context = runWithProfile(
                "multi-batch",
                "--contest.submission.judge.dispatch-mode=mysql")) {
            assertThatMissing(context, ContestJudgeOutboxRelay.class);
            assertThatMissing(context, ContestJudgeRabbitListener.class);
            assertThatMissing(context, MysqlContestJudgeDispatcher.class);
        }
    }

    private static ConfigurableApplicationContext runWithProfile(String profile, String... extraArguments) {
        SpringApplication application = new SpringApplication(ProfileTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        String[] arguments = new String[extraArguments.length + 4];
        arguments[0] = "--spring.profiles.active=" + profile;
        arguments[1] = "--spring.config.location=file:./src/main/resources/";
        arguments[2] = "--spring.main.banner-mode=off";
        arguments[3] = "--spring.jmx.enabled=false";
        System.arraycopy(extraArguments, 0, arguments, 4, extraArguments.length);
        return application.run(arguments);
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
            MysqlContestJudgeMetrics.class,
            MysqlContestJudgeDispatcher.class,
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
        MysqlContestJudgeProperties mysqlContestJudgeProperties() {
            return new MysqlContestJudgeProperties(1, 1, 1, java.time.Duration.ofSeconds(30),
                    java.time.Duration.ofHours(1));
        }

        @Bean
        JdbcContestSubmissionJudgeResultBatchPersistence judgeResultBatchPersistence() {
            return mock(JdbcContestSubmissionJudgeResultBatchPersistence.class);
        }

        @Bean
        ContestSubmissionJudgeResultStreamPublisher contestSubmissionJudgeResultStreamPublisher() {
            return mock(ContestSubmissionJudgeResultStreamPublisher.class);
        }
    }
}
