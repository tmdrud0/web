package my.oj.web.contest.scoreboard.delivery;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Keeps a scoreboard RabbitMQ Stream bean out of a context whose delivery is not {@code rabbit-stream}.
 *
 * <p>Placed beside each Stream bean's own {@code enabled} property rather than instead of it: the property
 * still decides which role consumes or publishes, and this condition makes sure that no role does so while
 * the MySQL poller is the delivery - so one judged result can never reach the scoreboard through both.
 * The validator additionally refuses the {@code enabled=true} flags in that delivery, so the gate is a
 * second line rather than a silent override.</p>
 */
public class RabbitStreamDeliveryCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !ContestScoreboardDelivery.isMySqlPoll(context.getEnvironment());
    }
}
