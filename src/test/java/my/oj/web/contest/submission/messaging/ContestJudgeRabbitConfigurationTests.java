package my.oj.web.contest.submission.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class ContestJudgeRabbitConfigurationTests {

    private final ContestJudgeRabbitConfiguration configuration = new ContestJudgeRabbitConfiguration();

    @Test
    void liveQueueIsDurableQuorumAndDeadLettersToConfiguredRoute() {
        Queue queue = configuration.contestJudgeLiveQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-exchange", ContestJudgeRabbitTopology.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", ContestJudgeRabbitTopology.DEAD_LETTER_ROUTING_KEY)
                .containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-overflow", "reject-publish");
    }

    @Test
    void deadLetterQueueIsDurableQuorum() {
        Queue queue = configuration.contestJudgeDeadLetterQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum");
    }

    @Test
    void resultStreamIsDurableWithBoundedRecoveryRetention() {
        Queue queue = configuration.contestJudgeResultStreamQueue();

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "stream")
                .containsEntry("x-max-age", "7D")
                .containsEntry("x-max-length-bytes", 10L * 1024 * 1024 * 1024);
    }
}
