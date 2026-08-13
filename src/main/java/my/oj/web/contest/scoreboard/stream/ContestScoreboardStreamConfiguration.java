package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.submission.messaging.ContestJudgeRabbitTopology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "contest.scoreboard.stream.consumer",
        name = "enabled",
        havingValue = "true"
)
class ContestScoreboardStreamConfiguration {

    @Bean("contestScoreboardStreamListenerContainer")
    SimpleMessageListenerContainer contestScoreboardStreamListenerContainer(
            ConnectionFactory connectionFactory,
            ContestScoreboardStreamListener listener,
            ContestScoreboardStreamConsumerProperties properties
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setPrefetchCount(properties.effectivePrefetch());
        container.setBatchSize(properties.effectiveBatchSize());
        container.setConsumerBatchEnabled(true);
        container.setReceiveTimeout(properties.effectiveReceiveTimeoutMillis());
        container.setBatchReceiveTimeout(properties.effectiveReceiveTimeoutMillis());
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(true);
        container.setMissingQueuesFatal(true);
        container.setAutoStartup(false);
        container.setMessageListener(listener);
        return container;
    }
}
