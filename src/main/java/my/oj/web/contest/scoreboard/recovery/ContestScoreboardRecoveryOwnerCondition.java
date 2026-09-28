package my.oj.web.contest.scoreboard.recovery;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Selects a recovery trigger only on the instance that declares itself the recovery owner.
 *
 * <h2>Why a condition rather than a check inside each trigger</h2>
 *
 * <p>{@code contest.scoreboard.recovery.owner.enabled} is read as a bean-definition condition so that
 * an instance which is not the owner has no trigger at all: not a trigger that returns early, and not
 * a trigger that logs that it declined. A runner that is registered and returns immediately still
 * occupies the name in the startup log and still appears in {@code /actuator/beans}; an operator
 * reading either cannot tell it from one that will fire. Removing the bean is the distinction the
 * setting claims to make, so the setting is enforced where the claim becomes true.</p>
 *
 * <p>What the gate does not do is make two owners safe. The pass gate behind these triggers is
 * JVM-local, so two instances that both declare themselves owners both run. The declaration narrows
 * who may recover; it never coordinates them - see {@code ARCHITECTURE.md} §3.5.</p>
 *
 * <p>The three mode triggers carry this condition. The full-replay <em>service</em> deliberately does
 * not: the retention-gap fallback replays through it from a stream delivery, so making the service
 * mode- or owner-exclusive would break the fallback that the {@code stream-offset} mode depends on.</p>
 *
 * <p>The default matches the properties record's, so an operator who says nothing gets the same
 * answer from both. The two are read independently - binding fills the record, this condition reads
 * the environment - which is why a change to one must be made to the other.</p>
 */
public class ContestScoreboardRecoveryOwnerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment().getProperty(
                ContestScoreboardRecoveryValidator.OWNER_PROPERTY, Boolean.class, true);
    }
}
