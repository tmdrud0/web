package my.oj.web.contest.scoreboard.delivery;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Selects the no-op judge result publisher whenever the RabbitMQ one is not registered.
 *
 * <p>The RabbitMQ publisher needs both {@code contest.submission.judge.result-stream.publisher.enabled=true}
 * and the {@code rabbit-stream} delivery. This is the exact complement, so the judge result writer always
 * finds one publisher and never two.</p>
 */
public class ResultStreamPublisherDisabledCondition implements Condition {

    public static final String PUBLISHER_PROPERTY = "contest.submission.judge.result-stream.publisher.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String enabled = context.getEnvironment().getProperty(PUBLISHER_PROPERTY);
        boolean publisherEnabled = enabled != null && "true".equalsIgnoreCase(enabled.trim());
        return !publisherEnabled || ContestScoreboardDelivery.isMySqlPoll(context.getEnvironment());
    }
}
