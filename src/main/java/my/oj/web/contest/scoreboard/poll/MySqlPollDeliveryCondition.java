package my.oj.web.contest.scoreboard.poll;

import my.oj.web.contest.scoreboard.delivery.ContestScoreboardDelivery;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches exactly when the Stream beans' {@code RabbitStreamDeliveryCondition} does not. */
class MySqlPollDeliveryCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return ContestScoreboardDelivery.isMySqlPoll(context.getEnvironment());
    }
}
