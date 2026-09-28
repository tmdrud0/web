package my.oj.web.contest.scoreboard.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which scoreboard Stream beans each delivery registers, read from the bean definitions the real
 * packages produce - the component scan and the configuration classes' {@code @Bean} methods, with every
 * condition evaluated - rather than from a list the test restates.
 *
 * <p>Definitions and not instances, because instantiating the Stream beans needs a broker; what this
 * test is about is whether they exist, and a bean without a definition cannot be created.</p>
 */
class ContestScoreboardDeliveryWiringTests {

    private static final String STREAM = "my.oj.web.contest.scoreboard.stream.";
    private static final String MESSAGING = "my.oj.web.contest.submission.messaging.";

    /** The production classes that carry the Stream path, plus the two publishers and the recovery factory. */
    private static final List<String> CANDIDATES = List.of(
            STREAM + "ContestScoreboardStreamConfiguration",
            STREAM + "ContestScoreboardStreamListener",
            STREAM + "ContestScoreboardStreamProcessor",
            STREAM + "ContestScoreboardStreamLifecycle",
            STREAM + "ContestScoreboardStreamRecoveryService",
            STREAM + "ContestScoreboardStreamPosition",
            STREAM + "ContestScoreboardStreamMetrics",
            STREAM + "ContestScoreboardStreamTailOffsetMonitor",
            STREAM + "ContestScoreboardStreamScheduleConfiguration",
            STREAM + "ContestScoreboardAppliedAtCompletion",
            STREAM + "ContestScoreboardRebuildEndpoint",
            MESSAGING + "RabbitContestSubmissionJudgeResultStreamPublisher",
            MESSAGING + "ContestJudgeRabbitConfiguration",
            "my.oj.web.contest.submission.judge.DisabledContestSubmissionJudgeResultStreamPublisher",
            "my.oj.web.contest.scoreboard.recovery.ContestScoreboardRecoveryStrategyConfig"
    );

    /** Every bean that moves judge results to the scoreboard over the RabbitMQ Stream. */
    private static final Set<String> STREAM_BEANS = Set.of(
            STREAM + "ContestScoreboardStreamConfiguration",
            STREAM + "ContestScoreboardStreamListener",
            STREAM + "ContestScoreboardStreamProcessor",
            STREAM + "ContestScoreboardStreamLifecycle",
            STREAM + "ContestScoreboardStreamRecoveryService",
            STREAM + "ContestScoreboardStreamPosition",
            STREAM + "ContestScoreboardStreamTailOffsetMonitor",
            STREAM + "ContestScoreboardAppliedAtCompletion",
            MESSAGING + "RabbitContestSubmissionJudgeResultStreamPublisher",
            "contestScoreboardStreamListenerContainer",
            "contestJudgeResultStreamRabbitTemplate",
            "contestJudgeResultStreamQueue",
            "contestJudgeResultStreamBinding",
            "contestScoreboardRecoveryStrategy"
    );

    private static final String DISABLED_PUBLISHER =
            "my.oj.web.contest.submission.judge.DisabledContestSubmissionJudgeResultStreamPublisher";

    @Test
    void theMySqlPollerRegistersNoStreamPublisherOrConsumer() {
        // Even with both Stream flags on: the delivery gate removes the beans, and the validator
        // separately refuses the flags so this configuration never starts.
        Set<String> beans = beanNames(
                "contest.scoreboard.recovery.mode", "redis-seq",
                "contest.scoreboard.delivery", "mysql-poll",
                "contest.scoreboard.stream.consumer.enabled", "true",
                "contest.submission.judge.result-stream.publisher.enabled", "true",
                "contest.submission.judge.rabbit.publisher.enabled", "true");

        assertThat(beans).doesNotContainAnyElementsOf(STREAM_BEANS);
        assertThat(beans)
                .as("the judge still publishes nothing, successfully, and the work queue stays")
                .contains(DISABLED_PUBLISHER, "contestJudgeLiveQueue");
    }

    @Test
    void aMisspeltPollerDeliveryStillKeepsTheStreamOff() {
        Set<String> beans = beanNames(
                "contest.scoreboard.delivery", "MYSQL_POLL",
                "contest.scoreboard.stream.consumer.enabled", "true",
                "contest.submission.judge.result-stream.publisher.enabled", "true");

        assertThat(beans).doesNotContainAnyElementsOf(STREAM_BEANS);
    }

    @Test
    void theStreamDeliveryKeepsEveryStreamBean() {
        Set<String> beans = beanNames(
                "contest.scoreboard.recovery.mode", "stream-offset",
                "contest.scoreboard.stream.consumer.enabled", "true",
                "contest.submission.judge.result-stream.publisher.enabled", "true",
                "contest.submission.judge.rabbit.publisher.enabled", "true");

        assertThat(beans).containsAll(STREAM_BEANS);
        assertThat(beans).doesNotContain(DISABLED_PUBLISHER);
    }

    /**
     * The bean names and bean classes the candidates register once every condition - class-level and on
     * each {@code @Bean} method - has been evaluated against these properties.
     */
    private static Set<String> beanNames(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int index = 0; index < properties.length; index += 2) {
            environment.setProperty(properties[index], properties[index + 1]);
        }
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(registry, environment);
        for (String candidate : CANDIDATES) {
            try {
                Class<?> type = Class.forName(candidate);
                reader.registerBean(type, candidate);
            } catch (ClassNotFoundException missing) {
                throw new AssertionError(candidate + " is no longer on the classpath", missing);
            }
        }
        ConfigurationClassPostProcessor configurationClasses = new ConfigurationClassPostProcessor();
        configurationClasses.setEnvironment(environment);
        configurationClasses.setResourceLoader(new DefaultResourceLoader());
        configurationClasses.postProcessBeanDefinitionRegistry(registry);
        Set<String> names = new HashSet<>(Arrays.asList(registry.getBeanDefinitionNames()));
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            if (definition.getBeanClassName() != null) {
                names.add(definition.getBeanClassName());
            }
        }
        return names;
    }
}
