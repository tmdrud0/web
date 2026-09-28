package my.oj.web.contest.scoreboard.stream;

import my.oj.web.contest.scoreboard.delivery.RabbitStreamDeliveryCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Carries a rollback the checkpoint CAS found from the listener to the lifecycle that answers it.
 *
 * <p>The listener cannot hold the lifecycle: the lifecycle is built on the listener's container, which is
 * built on the listener. This sits between them - the listener raises the signal, the lifecycle registers
 * the handler when it is constructed - so the answer does not have to wait for the supervisor's next pass.
 * With no lifecycle registered (a slice test), the signal is dropped and the supervisor remains the path
 * that answers.</p>
 */
@Component
@ConditionalOnProperty(prefix = "contest.scoreboard.stream.consumer", name = "enabled", havingValue = "true")
@Conditional(RabbitStreamDeliveryCondition.class)
class ContestScoreboardStreamRollbackSignal {

    /** What the lifecycle does with a refusal. */
    @FunctionalInterface
    interface Handler {
        void checkpointRegressed(long consumerGeneration, long expectedFloor);
    }

    private volatile Handler handler = (generation, floor) -> {
    };

    void register(Handler handler) {
        this.handler = handler == null ? (generation, floor) -> { } : handler;
    }

    void checkpointRegressed(long consumerGeneration, long expectedFloor) {
        handler.checkpointRegressed(consumerGeneration, expectedFloor);
    }
}
