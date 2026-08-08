package my.oj.web.contest.submission.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${contest.submission.judge.rabbit.publisher.enabled:false}' == 'true' || "
        + "'${contest.submission.judge.rabbit.listener.enabled:false}' == 'true' || "
        + "'${contest.submission.judge.result-stream.publisher.enabled:false}' == 'true'")
class ContestJudgeRabbitConfiguration {

    @Bean
    Jackson2JsonMessageConverter contestJudgeMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    DirectExchange contestJudgeExchange() {
        return new DirectExchange(ContestJudgeRabbitTopology.EXCHANGE, true, false);
    }

    @Bean
    DirectExchange contestJudgeDeadLetterExchange() {
        return new DirectExchange(ContestJudgeRabbitTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue contestJudgeLiveQueue() {
        return QueueBuilder.durable(ContestJudgeRabbitTopology.LIVE_QUEUE)
                .quorum()
                .deadLetterExchange(ContestJudgeRabbitTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ContestJudgeRabbitTopology.DEAD_LETTER_ROUTING_KEY)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-overflow", "reject-publish")
                .build();
    }

    @Bean
    Queue contestJudgeDeadLetterQueue() {
        return QueueBuilder.durable(ContestJudgeRabbitTopology.DEAD_LETTER_QUEUE)
                .quorum()
                .build();
    }

    @Bean
    Queue contestJudgeResultStreamQueue() {
        return QueueBuilder.durable(ContestJudgeRabbitTopology.RESULT_STREAM_QUEUE)
                .stream()
                .withArgument("x-max-age", ContestJudgeRabbitTopology.RESULT_STREAM_MAX_AGE)
                .withArgument(
                        "x-max-length-bytes",
                        ContestJudgeRabbitTopology.RESULT_STREAM_MAX_LENGTH_BYTES
                )
                .build();
    }

    @Bean
    Binding contestJudgeLiveBinding(Queue contestJudgeLiveQueue, DirectExchange contestJudgeExchange) {
        return BindingBuilder.bind(contestJudgeLiveQueue)
                .to(contestJudgeExchange)
                .with(ContestJudgeRabbitTopology.LIVE_ROUTING_KEY);
    }

    @Bean
    Binding contestJudgeDeadLetterBinding(Queue contestJudgeDeadLetterQueue,
                                          DirectExchange contestJudgeDeadLetterExchange) {
        return BindingBuilder.bind(contestJudgeDeadLetterQueue)
                .to(contestJudgeDeadLetterExchange)
                .with(ContestJudgeRabbitTopology.DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    Binding contestJudgeResultStreamBinding(Queue contestJudgeResultStreamQueue,
                                            DirectExchange contestJudgeExchange) {
        return BindingBuilder.bind(contestJudgeResultStreamQueue)
                .to(contestJudgeExchange)
                .with(ContestJudgeRabbitTopology.RESULT_STREAM_ROUTING_KEY);
    }

    @Bean("contestJudgeRabbitTemplate")
    @ConditionalOnProperty(prefix = "contest.submission.judge.rabbit.publisher", name = "enabled", havingValue = "true")
    RabbitTemplate contestJudgeRabbitTemplate(ConnectionFactory connectionFactory,
                                              Jackson2JsonMessageConverter contestJudgeMessageConverter) {
        return publisherConfirmRabbitTemplate(connectionFactory, contestJudgeMessageConverter);
    }

    @Bean("contestJudgeResultStreamRabbitTemplate")
    @ConditionalOnProperty(
            prefix = "contest.submission.judge.result-stream.publisher",
            name = "enabled",
            havingValue = "true"
    )
    RabbitTemplate contestJudgeResultStreamRabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter contestJudgeMessageConverter
    ) {
        return publisherConfirmRabbitTemplate(connectionFactory, contestJudgeMessageConverter);
    }

    private static RabbitTemplate publisherConfirmRabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter
    ) {
        if (connectionFactory instanceof CachingConnectionFactory cachingConnectionFactory) {
            cachingConnectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            cachingConnectionFactory.setPublisherReturns(true);
        }
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }

    @Bean("contestJudgeRabbitListenerContainerFactory")
    @ConditionalOnProperty(prefix = "contest.submission.judge.rabbit.listener", name = "enabled", havingValue = "true")
    SimpleRabbitListenerContainerFactory contestJudgeRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter contestJudgeMessageConverter,
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts:3}") int maxAttempts,
            @Value("${spring.rabbitmq.listener.simple.retry.initial-interval:1s}") java.time.Duration initialInterval,
            @Value("${spring.rabbitmq.listener.simple.retry.multiplier:2}") double multiplier,
            @Value("${spring.rabbitmq.listener.simple.retry.max-interval:10s}") java.time.Duration maxInterval) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(contestJudgeMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(Math.max(1, maxAttempts))
                .backOffOptions(initialInterval.toMillis(), multiplier, maxInterval.toMillis())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        factory.setErrorHandler(new ConditionalRejectingErrorHandler());
        return factory;
    }
}
